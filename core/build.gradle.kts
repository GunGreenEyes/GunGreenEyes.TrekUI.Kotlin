// :core - the shared TrekUI library every TrekUI app (launcher, Messages, Keyboard, Lock Screen) builds on:
// interface/theme model (Skin, Palette, Era, TrekPrefs), Canvas drawing helpers (Ui, ConsoleFrame/Card/Dialog,
// Imperial/Collective/Pipe/Era draw code), ScreenMetrics, fonts and glyphs, Sfx + synth sounds + the
// downloadable sound library, licensing (trial + the shared trekui_unlock purchase) and the suite contract
// (SuiteContract / SuiteClient / SuiteProvider). No product flavors: per-app values (trial length, product
// id, flavor) come from the app through com.gungreeneyes.trekui.core.CoreConfig.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.gungreeneyes.trekui.core"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = false
        xmlReport = true
        htmlReport = true
    }
}

dependencies {
    // api: apps call ScreenMetrics (androidx.window) and BillingManager types directly.
    api(libs.androidx.window)
    api(libs.billing)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.orgjson) // real org.json on the JVM (android.jar only stubs it)
}
