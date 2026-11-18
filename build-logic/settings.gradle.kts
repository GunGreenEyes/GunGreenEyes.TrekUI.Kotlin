// build-logic: the convention plugins every TrekUI module applies (see docs/BUILD.md). An included build
// (settings.gradle.kts: pluginManagement { includeBuild("build-logic") }), so the conventions compile with
// the build and share the root version catalog.
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "build-logic"
include(":convention")
