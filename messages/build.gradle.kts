// :messages - TrekUI Messages, a full default SMS/MMS app drawn in the TrekUI interfaces (LCA-T31).
// The same flavors, signing and suite-permission placeholder as every app (the trekui.android.application
// convention in build-logic/), so a develop build installs beside the Play build and only talks to develop
// siblings:
//   develop    - com.gungreeneyes.trekui.messages.dev, 12-hour trial sessions, like the store build
//   production - com.gungreeneyes.trekui.messages, the Google Play build
plugins {
    alias(libs.plugins.trekui.android.application)
}

android {
    namespace = "com.gungreeneyes.trekui.messages"

    defaultConfig {
        applicationId = "com.gungreeneyes.trekui.messages"
    }

    productFlavors {
        getByName("develop") {
            resValue("string", "app_name", "TrekUI Messages Dev")
        }
        getByName("production") {
            resValue("string", "app_name", "TrekUI Messages")
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity)
    // ActivityResult APIs need Fragment 1.3+; an older one arrives transitively
    // (lint InvalidFragmentVersionForActivityResult).
    implementation(libs.androidx.fragment)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
