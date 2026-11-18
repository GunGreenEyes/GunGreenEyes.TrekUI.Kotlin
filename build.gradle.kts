// Root build: plugin versions come from gradle/libs.versions.toml. AGP and detekt are declared here (not
// applied) so the build-logic convention plugins, which compile against them, run the same versions; each
// module applies trekui.android.application or trekui.android.library (build-logic/, docs/BUILD.md).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.detekt) apply false
}

// ./gradlew dist: builds every app (develop debug APK, production release APK + AAB) and copies them into
// dist/<versionName>/ as <Prefix>-<versionName>-<flavor>-<buildType>.<ext>, e.g. dist/45/TrekUI-45-production-release.apk,
// dist/45/TrekUI-Messages-45-develop-debug.apk, dist/45/TrekUI-Keyboard-45-production-release.aab. The per-app prefix
// keeps the four apps' files apart. dist/ is committed: one folder per released version, so earlier APKs stay available.
val distApps = linkedMapOf(
    ":app" to "TrekUI",
    ":messages" to "TrekUI-Messages",
    ":keyboard" to "TrekUI-Keyboard",
    ":lockscreen" to "TrekUI-LockScreen",
)
tasks.register<Copy>("dist") {
    group = "distribution"
    description = "Builds every app's develop debug APK and production release APK + AAB and copies them into dist/<versionName>/."
    val versionName = java.util.Properties()
        .apply { file("version.properties").inputStream().use { load(it) } }
        .getProperty("versionName")
    distApps.forEach { (path, prefix) ->
        val module = path.removePrefix(":")
        dependsOn("$path:assembleDevelopDebug", "$path:assembleProductionRelease", "$path:bundleProductionRelease")
        val out = project(path).layout.buildDirectory.dir("outputs")
        listOf("apk/develop/debug", "apk/production/release", "bundle/productionRelease").forEach { dir ->
            from(out.map { it.dir(dir) }) {
                include("$module-*.apk", "$module-*.aab")
                rename("^$module-(.+)$", "$prefix-$versionName-\$1")
            }
        }
    }
    into(layout.projectDirectory.dir("dist/$versionName"))
    includeEmptyDirs = false
}
