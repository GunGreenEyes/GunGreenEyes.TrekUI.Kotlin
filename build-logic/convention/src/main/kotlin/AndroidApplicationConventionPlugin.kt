import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * `trekui.android.application`: one of the four TrekUI apps (launcher, Messages, Keyboard, Lock Screen).
 *
 * Every app shares the version (version.properties, versionName == versionCode), the trial and unlock
 * BuildConfig fields, two product flavors (develop: `.dev` id, `-dev` name, 5-minute trial sessions, the
 * develop suite permission; production: the Google Play build), release signing with the Play upload key
 * (signing/release.properties locally, TREKUI_* environment variables in CI, the debug key when neither is
 * there), R8 with resource shrinking, and the suite permission placeholder `trekuiSuitePermission`. The app's
 * own build.gradle.kts adds its namespace, applicationId, flavor `app_name`s and dependencies.
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")
            pluginManager.apply(QualityConventionPlugin::class.java)

            val version = rootProperties("version.properties")
            val signingProps = rootProperties("signing/release.properties")
            fun signingValue(key: String): String? =
                providers.environmentVariable("TREKUI_$key").orNull ?: signingProps.getProperty(key)

            extensions.configure<ApplicationExtension> {
                configureTrekUiAndroid()

                defaultConfig.apply {
                    targetSdk = TrekUiSdk.TARGET
                    versionCode = version.getProperty("versionCode").toInt()
                    versionName = version.getProperty("versionName")
                    // Trial: how long an unlicensed copy runs per session before showing the purchase screen.
                    // One trekui_unlock purchase unlocks every app.
                    buildConfigField("long", "TRIAL_SESSION_MINUTES", "1440")
                    buildConfigField("String", "UNLOCK_PRODUCT_ID", "\"trekui_unlock\"")
                    manifestPlaceholders["trekuiSuitePermission"] = "com.gungreeneyes.trekui.permission.SUITE"
                }

                flavorDimensions += "channel"
                productFlavors.create("develop").apply {
                    dimension = "channel"
                    applicationIdSuffix = ".dev"
                    versionNameSuffix = "-dev"
                    buildConfigField("long", "TRIAL_SESSION_MINUTES", "5")
                    manifestPlaceholders["trekuiSuitePermission"] = "com.gungreeneyes.trekui.dev.permission.SUITE"
                }
                productFlavors.create("production").apply {
                    dimension = "channel"
                }

                // Debug builds use the SDK's default debug key. Release uses the Play upload key, the same for
                // every app because the suite permission is signature-level.
                signingConfigs.create("release").apply {
                    val store = signingValue("STORE_FILE")
                    if (store != null) {
                        storeFile = rootProject.file(store)
                        storePassword = signingValue("STORE_PASSWORD")
                        keyAlias = signingValue("KEY_ALIAS")
                        keyPassword = signingValue("KEY_PASSWORD")
                    }
                }

                buildTypes.getByName("debug").apply {
                    isDebuggable = true
                }
                buildTypes.getByName("release").apply {
                    isMinifyEnabled = true
                    isShrinkResources = true
                    proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
                    signingConfig = if (signingValue("STORE_FILE") != null) {
                        signingConfigs.getByName("release")
                    } else {
                        signingConfigs.getByName("debug")
                    }
                }

                buildFeatures.buildConfig = true
                buildFeatures.resValues = true

                packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
            }
        }
    }
}
