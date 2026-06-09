pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        maven { setUrl("https://maven.pkg.github.com/libxposed/api") }
    }
}

rootProject.name = "Fcitx5FrostedGlass"
include(":app")
