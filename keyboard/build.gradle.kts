// :keyboard - TrekUI Keyboard, an input method (IME) drawn in the TrekUI interfaces (LCA-T32).
// The same flavors, signing and suite-permission placeholder as every app (the trekui.android.application
// convention in build-logic/), so a develop build installs beside the Play build and only talks to develop
// siblings:
//   develop    - com.gungreeneyes.trekui.keyboard.dev, 5-minute trial sessions for testing
//   production - com.gungreeneyes.trekui.keyboard, the Google Play build
// The keyboard has no INTERNET permission: nothing typed ever leaves the device (docs/KEYBOARD.md).
plugins {
    alias(libs.plugins.trekui.android.application)
}

android {
    namespace = "com.gungreeneyes.trekui.keyboard"

    defaultConfig {
        applicationId = "com.gungreeneyes.trekui.keyboard"
    }

    productFlavors {
        getByName("develop") {
            resValue("string", "app_name", "TrekUI Keyboard Dev")
        }
        getByName("production") {
            resValue("string", "app_name", "TrekUI Keyboard")
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
