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
        // RootEncoder (rtmp-rtsp-stream-client) is published via JitPack
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "ScreenStreamApp"
include(":app")
