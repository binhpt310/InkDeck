pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "InkDeck"

include(":app")
include(":core-eink")

include(":core-data")
include(":core-net")
include(":feature-terminal")
include(":feature-tasks")
include(":feature-telegram")
include(":feature-market")
include(":feature-ai")
