plugins {
    id("fr.smolder.hytale.dev") version "0.0.10"
}

dependencies {
    implementation(project(":api"))
    implementation(project(":kernel"))
    implementation(project(":module"))
    implementation(project(":gamesystem"))
}

hytale {
    // Optional: Override Hytale installation path (defaults to OS-specific standard location)
    // hytalePath.set("...")

    // Optional: patch line (defaults to "release")
    patchLine.set("release")

    // Optional: game version (defaults to "latest")
    gameVersion.set("latest")

    // Auto-update manifest.json during build? (defaults to true)
    autoUpdateManifest.set(true)

    // Memory configuration
    minMemory.set("2G")
    maxMemory.set("4G")

    // Use AOT cache for faster startup (defaults to true)
    useAotCache.set(false)

    // Decompilation settings
    vineflowerVersion.set("1.11.2")
    decompileFilter.set(listOf("com/hypixel/**"))
    decompilerHeapSize.set("6G")

    // Automatically attach decompiled sources to IDE (defaults to true)
    includeDecompiledSources.set(true)

    manifest {
        group = "org.vttale"
        name = "VTTale"
        version = project.version.toString() // Auto-syncs with project version
        description = "VTTale is a Virtual Tabletop platform for Hytale."


        // Or simply by name
        author("DragoSpiro98")

        serverVersion = "*"

        // Plugin-specific
        main = "org.vttale.vttale.platform.hytale.VTTaleHytalePlugin"
        includesAssetPack = true
        disabledByDefault = false
    }
}

tasks.named("sourcesJar") {
    dependsOn("generateManifest")
}
