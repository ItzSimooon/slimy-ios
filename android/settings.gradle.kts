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

rootProject.name = "Slimy"
// :model is the game itself in plain Kotlin (a port of the iOS app's
// Shared/Model and the arcade engines), unit-tested on the JVM.
// :app is everything Android: Compose UI, online, notifications, widgets.
include(":model", ":app")
