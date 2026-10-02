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
        // sherpa-onnx AAR (k2-fsa) — JitPack
        maven("https://jitpack.io")
    }
}
rootProject.name = "HeyStrike"
include(":app")
