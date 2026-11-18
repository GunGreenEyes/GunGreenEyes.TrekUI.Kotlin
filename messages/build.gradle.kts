// :messages - TrekUI Messages, a full default SMS/MMS app drawn in the TrekUI interfaces (LCA-T31).
// Same two product flavors, signing and suite-permission placeholder as :app, so a develop build installs
// beside the Play build and only talks to develop siblings:
//   develop    - com.gungreeneyes.trekui.messages.dev, 5-minute trial sessions for testing
//   production - com.gungreeneyes.trekui.messages, the Google Play build
// Release signing reads signing/release.properties locally or TREKUI_* environment variables in CI.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val versionProps = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }
val signingProps = Properties().apply {
    val f = rootProject.file("signing/release.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(key: String): String? = System.getenv("TREKUI_$key") ?: signingProps.getProperty(key)

android {
    namespace = "com.gungreeneyes.trekui.messages"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.gungreeneyes.trekui.messages"
        minSdk = 26
        targetSdk = 37
        versionCode = versionProps.getProperty("versionCode").toInt()
        versionName = versionProps.getProperty("versionName")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Same trial rule as the launcher: 24-hour sessions; one trekui_unlock purchase unlocks every app.
        buildConfigField("long", "TRIAL_SESSION_MINUTES", "1440")
        buildConfigField("String", "UNLOCK_PRODUCT_ID", "\"trekui_unlock\"")
        manifestPlaceholders["trekuiSuitePermission"] = "com.gungreeneyes.trekui.permission.SUITE"
    }

    flavorDimensions += "channel"
    productFlavors {
        create("develop") {
            dimension = "channel"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            resValue("string", "app_name", "TrekUI Messages Dev")
            buildConfigField("long", "TRIAL_SESSION_MINUTES", "5")
            manifestPlaceholders["trekuiSuitePermission"] = "com.gungreeneyes.trekui.dev.permission.SUITE"
        }
        create("production") {
            dimension = "channel"
            resValue("string", "app_name", "TrekUI Messages")
        }
    }

    signingConfigs {
        // Debug builds use the SDK's default debug key. Release uses the Play upload key (the same key as
        // the launcher: the suite permission is signature-level).
        create("release") {
            val store = signingValue("STORE_FILE")
            if (store != null) {
                storeFile = rootProject.file(store)
                storePassword = signingValue("STORE_PASSWORD")
                keyAlias = signingValue("KEY_ALIAS")
                keyPassword = signingValue("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            isDebuggable = true
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (signingValue("STORE_FILE") != null) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
        resValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = true
        xmlReport = true
        htmlReport = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity)
    // ActivityResult APIs need Fragment 1.3+; an older one arrives transitively (lint InvalidFragmentVersionForActivityResult).
    implementation(libs.androidx.fragment)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
