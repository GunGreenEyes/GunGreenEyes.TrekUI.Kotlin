// :lockscreen - TrekUI Lock Screen, a wake screen shown over the keyguard (LCA-T33). A cosmetic layer only:
// unlocking always goes through the system keyguard (PIN / pattern / biometric). The same flavors, signing
// and suite-permission placeholder as every app (the trekui.android.application convention in
// build-logic/), so a develop build installs beside the Play build and only talks to develop siblings:
//   develop    - com.gungreeneyes.trekui.lockscreen.dev, 12-hour trial sessions, like the store build
//   production - com.gungreeneyes.trekui.lockscreen, the Google Play build
plugins {
    alias(libs.plugins.trekui.android.application)
}

android {
    namespace = "com.gungreeneyes.trekui.lockscreen"

    defaultConfig {
        applicationId = "com.gungreeneyes.trekui.lockscreen"
    }

    productFlavors {
        getByName("develop") {
            resValue("string", "app_name", "TrekUI Lock Screen Dev")
        }
        getByName("production") {
            resValue("string", "app_name", "TrekUI Lock Screen")
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
