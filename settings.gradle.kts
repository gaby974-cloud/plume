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
        // SDK stylet et e-ink Onyx (Boox)
        maven { url = uri("https://repo.boox.com/repository/maven-public/") }
    }
}

rootProject.name = "CarnetEncre"
include(":app")
