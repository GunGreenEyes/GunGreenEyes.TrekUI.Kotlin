// App module, the TrekUI launcher. The shared app setup (SDK levels, version, develop/production flavors,
// release signing, R8, lint, ktlint/detekt) is the trekui.android.application convention in build-logic/:
//   develop    - com.gungreeneyes.trekui.dev: installs beside the store build, same 12-hour trial
//   production - com.gungreeneyes.trekui: the Google Play build, "TrekUI Launcher"
plugins {
    alias(libs.plugins.trekui.android.application)
}

android {
    namespace = "com.gungreeneyes.trekui"

    defaultConfig {
        applicationId = "com.gungreeneyes.trekui"
        // Play restricts READ_CALL_LOG to default phone apps; only develop builds read the call log.
        buildConfigField("boolean", "CALL_LOG_ENABLED", "false")
    }

    productFlavors {
        getByName("develop") {
            resValue("string", "app_name", "TrekUI Dev")
            buildConfigField("boolean", "CALL_LOG_ENABLED", "true")
        }
        getByName("production") {
            resValue("string", "app_name", "TrekUI")
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        animationsDisabled = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.window)
    implementation(libs.billing)
    implementation(libs.profileinstaller)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.orgjson) // real org.json on the JVM (android.jar only stubs it)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.espresso)
    androidTestImplementation(libs.uiautomator)
    androidTestImplementation(libs.truth)
}

// dist/ for every app (this one included) is the root project's `dist` task (build.gradle.kts).
