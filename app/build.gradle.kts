// App module. Two product flavors share one codebase:
//   develop    - com.gungreeneyes.trekui.dev: installs beside the store build, 5-minute trial for testing
//   production - com.gungreeneyes.trekui: the Google Play build, "TrekUI Launcher"
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
    namespace = "com.gungreeneyes.trekui"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.gungreeneyes.trekui"
        minSdk = 26
        targetSdk = 37
        versionCode = versionProps.getProperty("versionCode").toInt()
        versionName = versionProps.getProperty("versionName")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Trial: how long an unlicensed copy runs per session before showing the purchase screen.
        buildConfigField("long", "TRIAL_SESSION_MINUTES", "1440")
        buildConfigField("String", "UNLOCK_PRODUCT_ID", "\"trekui_unlock\"")
        // Play restricts READ_CALL_LOG to default phone apps; only develop builds read the call log.
        buildConfigField("boolean", "CALL_LOG_ENABLED", "false")
        manifestPlaceholders["trekuiSuitePermission"] = "com.gungreeneyes.trekui.permission.SUITE"
    }

    flavorDimensions += "channel"
    productFlavors {
        create("develop") {
            dimension = "channel"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            resValue("string", "app_name", "TrekUI Dev")
            buildConfigField("long", "TRIAL_SESSION_MINUTES", "5")
            buildConfigField("boolean", "CALL_LOG_ENABLED", "true")
            manifestPlaceholders["trekuiSuitePermission"] = "com.gungreeneyes.trekui.dev.permission.SUITE"
        }
        create("production") {
            dimension = "channel"
            resValue("string", "app_name", "TrekUI")
        }
    }

    signingConfigs {
        // Debug builds use the SDK's default debug key. Release uses the Play upload key.
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
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
        animationsDisabled = true
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
