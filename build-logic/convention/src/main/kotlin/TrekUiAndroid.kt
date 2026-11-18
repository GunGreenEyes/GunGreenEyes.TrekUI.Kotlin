import com.android.build.api.dsl.CommonExtension
import java.util.Properties
import org.gradle.api.JavaVersion
import org.gradle.api.Project

/** SDK levels and JVM target shared by every TrekUI module. */
internal object TrekUiSdk {
    const val COMPILE = 37
    const val MIN = 26
    const val TARGET = 37
    val JAVA = JavaVersion.VERSION_17
}

/**
 * What the library and the four apps share: SDK levels, Java/Kotlin target 17 (AGP's built-in Kotlin takes
 * its jvmTarget from targetCompatibility), the instrumentation runner, JVM unit tests that get default values
 * from android.jar stubs, and lint that reports (XML + HTML) instead of failing the build.
 */
internal fun CommonExtension.configureTrekUiAndroid() {
    compileSdk = TrekUiSdk.COMPILE
    defaultConfig.minSdk = TrekUiSdk.MIN
    defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    compileOptions.sourceCompatibility = TrekUiSdk.JAVA
    compileOptions.targetCompatibility = TrekUiSdk.JAVA
    testOptions.unitTests.isReturnDefaultValues = true
    lint.abortOnError = false
    lint.checkReleaseBuilds = true
    lint.xmlReport = true
    lint.htmlReport = true
}

/** Reads a properties file under the root project; empty when the file is missing (e.g. no signing/ locally). */
internal fun Project.rootProperties(path: String): Properties {
    val text = providers.fileContents(rootProject.layout.projectDirectory.file(path)).asText.orNull
    return Properties().apply { if (text != null) load(text.reader()) }
}
