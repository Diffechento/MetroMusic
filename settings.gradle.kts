pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // MangoTile comes from mavenCentral() below, so a plain clone builds. mavenLocal() is
        // kept ahead of it only so that working on the framework takes effect here: publish it with
        //   gradlew :metro:publishToMavenLocal
        // in the MangoTile project and this build resolves that copy instead.
        mavenLocal()
        google()
        mavenCentral()
    }
}

rootProject.name = "MetroMusic"
include(":app")
