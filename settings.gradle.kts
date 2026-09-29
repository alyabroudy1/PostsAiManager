pluginManagement {
    includeBuild("build-logic")
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

// Architecture enforcement — scans the whole tree, so it must see every module's sources.
include(":architecture-test")

// Core
include(":core:common")
include(":core:model")
include(":core:domain")
include(":core:data")
include(":core:ai:core")
include(":core:ai:catalog")
include(":core:ai:local")
include(":core:ai:embed")
include(":core:download")
include(":core:ai:online")
include(":core:config")
include(":core:designsystem")
include(":core:testing")

// Features
include(":feature:home")
include(":feature:scanner")
include(":feature:documents")
include(":feature:chat")
include(":feature:profiles")
include(":feature:settings")
include(":feature:models")
