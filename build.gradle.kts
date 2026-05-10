import java.util.Properties

plugins {
    id("net.fabricmc.fabric-loom")
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("io.sentry.jvm.gradle")
    `maven-publish`
}

val modVersion = property("mod_version") as String
val mavenGroup = property("maven_group") as String
val archivesBaseName = property("archives_base_name") as String

val minecraftVersion = property("minecraft_version") as String
val loaderVersion = property("loader_version") as String
val fabricApiVersion = property("fabric_api_version") as String
val fabricKotlinVersion = property("fabric_kotlin_version") as String
// val fabricPermissionsVersion = property("fabric_permissions_version") as String
val zstdJniVersion = property("zstd_jni_version") as String
val tomlktVersion = property("tomlkt_version") as String
val kotlinxCoroutinesVersion = property("kotlinx_coroutines_version") as String
val junitVersion = property("junit_version") as String
val mockkVersion = property("mockk_version") as String
val sentryVersion = property("sentry_version") as String

version = modVersion
group = mavenGroup
base.archivesName.set(archivesBaseName)

repositories {
    mavenCentral()
    maven("https://maven.lucko.me/") { name = "Lucko" }
}

loom {
    mods {
        register("dbhvault") {
            sourceSet(sourceSets["main"])
        }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    implementation("net.fabricmc:fabric-loader:$loaderVersion")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

    // https://github.com/FabricMC/fabric-language-kotlin
    implementation("net.fabricmc:fabric-language-kotlin:$fabricKotlinVersion")

    // fabric-permissions-api was the original plan; no 26.1-compatible release
    // exists yet. Re-add when one ships and wire it into permissions/Permissions.kt.
    // implementation("me.lucko:fabric-permissions-api:$fabricPermissionsVersion")
    implementation("com.github.luben:zstd-jni:$zstdJniVersion")
    include("com.github.luben:zstd-jni:$zstdJniVersion")

    implementation("net.peanuuutz.tomlkt:tomlkt:$tomlktVersion")
    include("net.peanuuutz.tomlkt:tomlkt:$tomlktVersion")

    // Not included on purpose — fabric-language-kotlin ships kotlinx-coroutines-core
    // at runtime, so JIJ'ing it would shadow the FLK-provided copy.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinxCoroutinesVersion")

    implementation("io.sentry:sentry-log4j2:$sentryVersion")
    // Loom's include configuration is non-transitive; list sentry-core explicitly.
    // log4j-api/log4j-core are supplied by Minecraft and must not be re-bundled.
    include("io.sentry:sentry-log4j2:$sentryVersion")
    include("io.sentry:sentry:$sentryVersion")

    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.mockk:mockk:$mockkVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$kotlinxCoroutinesVersion")
}

val sentryDsn: String = rootProject.file("local.properties")
    .takeIf { it.exists() }
    ?.let { file ->
        Properties()
            .apply { file.inputStream().use { load(it) } }
            .getProperty("sentry.dsn", "")
    }
    ?: ""

tasks.processResources {
    val templateProps = mapOf(
        "version" to project.version,
        "sentry_dsn" to sentryDsn,
    )
    inputs.properties(templateProps)
    filteringCharset = "UTF-8"

    filesMatching("fabric.mod.json") {
        expand(templateProps)
    }
    filesMatching("dbhvault.build.properties") {
        expand(templateProps)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
}

kotlin {
    jvmToolchain(25)
}

java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    val projectName = project.name
    inputs.property("projectName", projectName)
    from("LICENSE") {
        rename { "${it}_$projectName" }
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
    repositories {
    }
}

val sentryAuthToken: String = System.getenv("SENTRY_AUTH_TOKEN").orEmpty()

sentry {
    // Uploads a JVM source bundle so Sentry stack traces show the actual
    // Kotlin source lines. Only enabled when SENTRY_AUTH_TOKEN is set in the
    // env (release builds); otherwise the source-bundle and upload tasks are
    // skipped entirely and the build is unaffected.
    includeSourceContext = sentryAuthToken.isNotBlank()
    org = "hskrasek"
    projectName = "dbhvault"
    authToken = sentryAuthToken
}

// Gradle 9 task-dependency validation: the Sentry plugin's generator tasks
// produce files under build/generated/sentry that the sourcesJar input set
// picks up, but the plugin doesn't wire the dependency. Declare it here so
// sourcesJar always runs after the Sentry generators.
tasks.named("sourcesJar") {
    dependsOn(
        "collectExternalDependenciesForSentry",
        "generateSentryBundleIdJava",
        "generateSentryDebugMetaPropertiesjava",
    )
}
