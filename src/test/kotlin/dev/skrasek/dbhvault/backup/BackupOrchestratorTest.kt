package dev.skrasek.dbhvault.backup

import dev.skrasek.dbhvault.backup.archive.BackupArchiver
import dev.skrasek.dbhvault.backup.storage.BackupEntry
import dev.skrasek.dbhvault.backup.storage.BackupRegistry
import dev.skrasek.dbhvault.backup.storage.RetentionDecision
import dev.skrasek.dbhvault.backup.storage.RetentionPolicy
import dev.skrasek.dbhvault.config.ArchiveFormat
import dev.skrasek.dbhvault.config.Config
import dev.skrasek.dbhvault.tempDir
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference

class BackupOrchestratorTest {

    private val testInstant: Instant = Instant.parse("2026-05-09T03:00:00Z")
    private val testClock: Clock = Clock.fixed(testInstant, ZoneOffset.UTC)

    // ---- Happy paths ----

    @Test
    fun `successful manual backup with name returns pinned Success`() {
        val orchestrator = newOrchestrator()
        val result = orchestrator.runIfFree(BackupRequest.Manual("snapshot")) as BackupResult.Success

        assertTrue(result.pinned)
        assertEquals(testInstant, result.timestamp)
        assertTrue(
            result.file.fileName.toString().contains("--snapshot"),
            "expected --snapshot in filename; got ${result.file.fileName}",
        )
    }

    @Test
    fun `successful manual backup without name returns unpinned Success`() {
        val orchestrator = newOrchestrator()
        val result = orchestrator.runIfFree(BackupRequest.Manual(null)) as BackupResult.Success

        assertFalse(result.pinned)
        assertFalse(
            result.file.fileName.toString().contains("--"),
            "unpinned filename should not contain --; got ${result.file.fileName}",
        )
    }

    @Test
    fun `successful scheduled backup returns unpinned Success`() {
        val orchestrator = newOrchestrator()
        val result = orchestrator.runIfFree(BackupRequest.Scheduled) as BackupResult.Success

        assertFalse(result.pinned)
    }

    // ---- Archiver invocation ----

    @Test
    fun `archiver invoked with worldDir source dest under backupDir and config level`() {
        val srcSlot = slot<Path>()
        val destSlot = slot<Path>()
        val levelSlot = slot<Int>()
        val archiver = mockk<BackupArchiver>()
        every { archiver.archive(capture(srcSlot), capture(destSlot), capture(levelSlot)) } answers {
            destSlot.captured.toFile().writeText("archived")
            1024L
        }

        val worldDir = makeWorldDir()
        val backupDir = tempDir("orch-backups-")
        val orchestrator = newOrchestrator(
            archiver = archiver,
            worldDir = worldDir,
            backupDir = backupDir,
        )

        orchestrator.runIfFree(BackupRequest.Manual("x"))

        assertEquals(worldDir, srcSlot.captured)
        assertTrue(
            destSlot.captured.startsWith(backupDir),
            "dest must live under backupDir; got ${destSlot.captured}",
        )
        // Default Config.compression.level == 3
        assertEquals(3, levelSlot.captured)
    }

    // ---- Concurrency lock ----

    @Test
    fun `concurrent invocation returns Skipped ALREADY_RUNNING`() {
        // The "concurrency" is logical re-entrance: the archiver mock invokes
        // runIfFree() while still inside the first call. Same lock semantics,
        // no threading required.
        var orchestratorRef: BackupOrchestrator? = null
        var nestedResult: BackupResult? = null

        val recursiveArchiver = object : BackupArchiver {
            override fun archive(sourceDir: Path, destFile: Path, level: Int): Long {
                nestedResult = orchestratorRef!!.runIfFree(BackupRequest.Manual("nested"))
                destFile.toFile().writeText("outer-archived")
                return 1L
            }
        }

        orchestratorRef = newOrchestrator(archiver = recursiveArchiver)

        val outer = orchestratorRef.runIfFree(BackupRequest.Manual("outer"))

        assertTrue(outer is BackupResult.Success, "outer should succeed; got $outer")
        assertTrue(nestedResult is BackupResult.Skipped, "nested should be skipped; got $nestedResult")
        assertEquals(
            BackupResult.SkipReason.ALREADY_RUNNING,
            (nestedResult as BackupResult.Skipped).reason,
        )
    }

    @Test
    fun `lock is released after successful backup`() {
        val orchestrator = newOrchestrator()
        val first = orchestrator.runIfFree(BackupRequest.Manual("a"))
        val second = orchestrator.runIfFree(BackupRequest.Manual("b"))

        assertTrue(first is BackupResult.Success)
        assertTrue(second is BackupResult.Success, "second call should succeed; got $second")
    }

    @Test
    fun `lock is released after failed backup`() {
        var calls = 0
        val flakyArchiver = object : BackupArchiver {
            override fun archive(sourceDir: Path, destFile: Path, level: Int): Long {
                calls++
                if (calls == 1) throw RuntimeException("boom")
                destFile.toFile().writeText("ok")
                return 1L
            }
        }
        val orchestrator = newOrchestrator(archiver = flakyArchiver)

        val first = orchestrator.runIfFree(BackupRequest.Manual("a"))
        val second = orchestrator.runIfFree(BackupRequest.Manual("b"))

        assertTrue(first is BackupResult.Failed, "first should fail; got $first")
        assertTrue(second is BackupResult.Success, "second should succeed after failure; got $second")
    }

    // ---- Failure handling ----

    @Test
    fun `archiver throwing returns Failed with the cause`() {
        val cause = RuntimeException("disk full")
        val archiver = mockk<BackupArchiver> {
            every { archive(any(), any(), any()) } throws cause
        }
        val orchestrator = newOrchestrator(archiver = archiver)

        val result = orchestrator.runIfFree(BackupRequest.Manual("x"))
        assertTrue(result is BackupResult.Failed)
        assertEquals(cause, (result as BackupResult.Failed).cause)
    }

    @Test
    fun `archiver throwing deletes the partial destination file`() {
        val archiver = object : BackupArchiver {
            override fun archive(sourceDir: Path, destFile: Path, level: Int): Long {
                // Simulate partial write before crash
                destFile.toFile().writeText("half-written-archive")
                throw RuntimeException("boom mid-write")
            }
        }
        val backupDir = tempDir("orch-cleanup-")
        val orchestrator = newOrchestrator(archiver = archiver, backupDir = backupDir)

        orchestrator.runIfFree(BackupRequest.Manual("x"))

        val leftover = backupDir.toFile().listFiles()?.toList().orEmpty()
        assertTrue(
            leftover.isEmpty(),
            "partial archive should be cleaned up; found ${leftover.map { it.name }}",
        )
    }

    // ---- Freeze / thaw lifecycle ----

    @Test
    fun `freeze invoked before archive`() {
        val order = mutableListOf<String>()
        val orchestrator = newOrchestrator(
            freeze = {
                order.add("freeze")
                AutoCloseable { order.add("thaw") }
            },
            archiver = object : BackupArchiver {
                override fun archive(sourceDir: Path, destFile: Path, level: Int): Long {
                    order.add("archive")
                    destFile.toFile().writeText("ok")
                    return 1L
                }
            },
        )

        orchestrator.runIfFree(BackupRequest.Manual("x"))

        assertEquals(listOf("freeze", "archive", "thaw"), order)
    }

    @Test
    fun `thaw invoked after successful archive`() {
        val tracker = TrackingCloseable()
        val orchestrator = newOrchestrator(freeze = { tracker })

        orchestrator.runIfFree(BackupRequest.Manual("x"))

        assertTrue(tracker.closed, "thaw must be called after a successful backup")
    }

    @Test
    fun `thaw invoked even when archive throws`() {
        val tracker = TrackingCloseable()
        val orchestrator = newOrchestrator(
            freeze = { tracker },
            archiver = mockk { every { archive(any(), any(), any()) } throws RuntimeException("boom") },
        )

        orchestrator.runIfFree(BackupRequest.Manual("x"))

        assertTrue(
            tracker.closed,
            "thaw must be called via finally even when archive throws — otherwise " +
                "autosave stays disabled and the world drifts out of sync",
        )
    }

    // ---- Plumbing ----

    @Test
    fun `backup directory is created if missing`() {
        val parent = tempDir("orch-parent-")
        val backupDir = parent.resolve("nested").resolve("backups")  // doesn't exist yet
        assertFalse(Files.exists(backupDir))

        val orchestrator = newOrchestrator(backupDir = backupDir)
        orchestrator.runIfFree(BackupRequest.Manual("x"))

        assertTrue(Files.isDirectory(backupDir), "orchestrator should auto-create backupDir")
    }

    @Test
    fun `retention pruning is applied with the policy's prune list`() {
        val toPrune = listOf(
            BackupEntry(
                path = Path.of("/tmp/world-old.tar.zst"),
                metadata = BackupMetadata(
                    timestamp = Instant.parse("2025-01-01T00:00:00Z"),
                    name = null,
                    format = ArchiveFormat.TAR_ZST,
                ),
                sizeBytes = 100L,
            ),
        )
        val captured = AtomicReference<List<BackupEntry>?>(null)

        val mockRetention = mockk<RetentionPolicy> {
            every { classify(any(), any()) } returns RetentionDecision(keep = emptyList(), prune = toPrune)
        }

        val orchestrator = newOrchestrator(
            retention = mockRetention,
            prune = { captured.set(it) },
        )

        orchestrator.runIfFree(BackupRequest.Manual("x"))

        val pruned = captured.get()
        assertNotNull(pruned, "prune callback should have been invoked")
        assertEquals(toPrune, pruned)
    }

    @Test
    fun `failed backup does not invoke prune`() {
        // Defensive: if the archive failed, we should not run retention against
        // the registry, since the registry's view is unchanged from before.
        val captured = AtomicReference<List<BackupEntry>?>(null)
        val failingArchiver = mockk<BackupArchiver> {
            every { archive(any(), any(), any()) } throws RuntimeException("boom")
        }

        val orchestrator = newOrchestrator(
            archiver = failingArchiver,
            prune = { captured.set(it) },
        )

        orchestrator.runIfFree(BackupRequest.Manual("x"))

        assertNull(captured.get(), "prune should not be called when the backup failed")
    }

    // ---- Helpers ----

    private fun newOrchestrator(
        config: Config = Config(),
        worldDir: Path = makeWorldDir(),
        backupDir: Path = tempDir("orch-backups-"),
        archiver: BackupArchiver = mockArchiver(),
        registry: BackupRegistry = BackupRegistry(tempDir("orch-registry-")),
        retention: RetentionPolicy = mockk(relaxed = true) {
            every { classify(any(), any()) } returns RetentionDecision(emptyList(), emptyList())
        },
        clock: Clock = testClock,
        freeze: () -> AutoCloseable = { AutoCloseable {} },
        prune: (List<BackupEntry>) -> Unit = {},
    ): BackupOrchestrator = BackupOrchestrator(
        config, worldDir, backupDir, archiver, registry, retention, clock, freeze, prune,
    )

    private fun makeWorldDir(): Path = tempDir("orch-world-").also {
        Files.writeString(it.resolve("level.dat"), "stub")
    }

    private fun mockArchiver(returnSize: Long = 1024L): BackupArchiver {
        val archiver = mockk<BackupArchiver>()
        every { archiver.archive(any(), any(), any()) } answers {
            // Materialize the dest file so happy-path tests see a real file at the
            // expected location.
            secondArg<Path>().toFile().writeText("archived-content")
            returnSize
        }
        return archiver
    }

    private class TrackingCloseable : AutoCloseable {
        var closed: Boolean = false
            private set

        override fun close() {
            closed = true
        }
    }
}
