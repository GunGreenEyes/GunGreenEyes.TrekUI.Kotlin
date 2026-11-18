// Convention plugins. AGP and detekt are compileOnly: the root build puts them on the classpath
// (`apply false` in build.gradle.kts), so every module runs the same plugin versions.
plugins {
    `kotlin-dsl`
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.detekt.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = libs.plugins.trekui.android.application.get().pluginId
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = libs.plugins.trekui.android.library.get().pluginId
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("quality") {
            id = libs.plugins.trekui.quality.get().pluginId
            implementationClass = "QualityConventionPlugin"
        }
    }
}
