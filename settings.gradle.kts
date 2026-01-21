pluginManagement {
    repositories {
        maven("https://repo.smolder.fr/public/")
        gradlePluginPortal()
    }
}
rootProject.name = "VTTale"
include("kernel")
include("api")
include("platform:hytale")
include("gamesystem")
include("module")