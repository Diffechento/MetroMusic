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
        // MangoTile is consumed from the local Maven repo. Publish it with
        //   gradlew :metro:publishToMavenLocal
        // in the MangoTile project after every change to the framework.
        mavenLocal()
        google()
        mavenCentral()
    }
}

rootProject.name = "MetroMusic"
include(":app")
