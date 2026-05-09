package dev.skrasek.dbhvault

import java.nio.file.Files
import java.nio.file.Path

internal fun tempDir(prefix: String = "dbhvault-test-"): Path {
    val dir = Files.createTempDirectory(prefix)
    Runtime.getRuntime().addShutdownHook(Thread {
        dir.toFile().deleteRecursively()
    })
    return dir
}
