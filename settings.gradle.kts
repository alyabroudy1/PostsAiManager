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
        google()
        mavenCentral()
    }
}

rootProject.name = "PostsAiManager"

// App
include(":app")

// Core
include(":core:common")
include(":core:model")
include(":core:domain")
include(":core:data")
include(":core:ai")
include(":core:designsystem")

// Features
include(":feature:home")
include(":feature:scanner")
include(":feature:documents")
include(":feature:chat")
include(":feature:profiles")
include(":feature:settings")
