pluginManagement {
    // Convention plugins (trekui.android.application, trekui.android.library, trekui.quality): docs/BUILD.md.
    includeBuild("build-logic")
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "TrekUI"
include(":core")
include(":app")
include(":messages")
include(":keyboard")
include(":lockscreen")
