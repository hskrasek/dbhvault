pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        gradlePluginPortal()
    }

    val loomVersion = settings.providers.gradleProperty("loom_version").get()
    val kotlinVersion = settings.providers.gradleProperty("kotlin_version").get()

    plugins {
        id("net.fabricmc.fabric-loom") version loomVersion
        kotlin("jvm") version kotlinVersion
        kotlin("plugin.serialization") version kotlinVersion
    }
}

rootProject.name = "dbhvault"
