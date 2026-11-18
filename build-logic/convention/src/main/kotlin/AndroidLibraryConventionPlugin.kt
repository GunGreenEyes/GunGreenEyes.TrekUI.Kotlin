import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** `trekui.android.library`: the shared `:core` library. No product flavors; apps pass per-app values in. */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")
            pluginManager.apply(QualityConventionPlugin::class.java)

            extensions.configure<LibraryExtension> {
                configureTrekUiAndroid()
            }
        }
    }
}
