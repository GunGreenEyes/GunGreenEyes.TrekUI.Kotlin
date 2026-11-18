import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.attributes.Bundling
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.PathSensitivity
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register

/**
 * `trekui.quality`: ktlint and detekt for a module's Kotlin sources (every source set under src/).
 *
 *  - `ktlintCheck` / `ktlintFormat` run the official ktlint CLI (version from the catalog) with the rules in the
 *    root .editorconfig. Violations the existing code already had are listed in the module's
 *    ktlint-baseline.xml, so only new ones fail.
 *  - `detekt` runs detekt's default rules; the module's detekt-baseline.xml holds the existing findings
 *    (regenerate with `detektBaseline`).
 *
 * Both are part of `check`. The Android plugins apply this; it is not meant to be applied on its own.
 */
class QualityConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
            val kotlinSources = fileTree("src") { include("**/*.kt") }

            pluginManager.apply("io.gitlab.arturbosch.detekt")
            extensions.configure<DetektExtension> {
                toolVersion = libs.findVersion("detekt").get().requiredVersion
                buildUponDefaultConfig = true
                source.setFrom(kotlinSources)
                baseline = file("detekt-baseline.xml")
            }
            // detekt 1.23 runs its own Kotlin compiler; keep the build's newer Kotlin out of its classpath.
            val detektKotlin = libs.findVersion("detekt-kotlin").get().requiredVersion
            configurations.matching { it.name == "detekt" }.configureEach {
                resolutionStrategy.eachDependency {
                    if (requested.group == "org.jetbrains.kotlin") useVersion(detektKotlin)
                }
            }

            val ktlint = configurations.create("ktlint") {
                isCanBeConsumed = false
            }
            dependencies.addProvider<MinimalExternalModuleDependency, ExternalModuleDependency>(
                "ktlint",
                libs.findLibrary("ktlint-cli").get(),
            ) {
                attributes {
                    attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling::class.java, Bundling.EXTERNAL))
                }
            }
            val editorConfig = rootProject.file(".editorconfig")
            val ktlintBaseline = file("ktlint-baseline.xml")

            val ktlintCheck = tasks.register<JavaExec>("ktlintCheck") {
                group = "verification"
                description = "Checks the Kotlin sources and build script with ktlint, minus ktlint-baseline.xml."
                classpath = ktlint
                mainClass.set("com.pinterest.ktlint.Main")
                inputs.files(kotlinSources, "build.gradle.kts", editorConfig, ktlintBaseline)
                    .withPathSensitivity(PathSensitivity.RELATIVE)
                args("--relative", "--baseline=${ktlintBaseline.name}", "src/**/*.kt", "*.kts")
            }
            tasks.register<JavaExec>("ktlintFormat") {
                group = "formatting"
                description = "Rewrites the module's Kotlin sources and build script to the ktlint rules."
                classpath = ktlint
                mainClass.set("com.pinterest.ktlint.Main")
                args("--relative", "-F", "src/**/*.kt", "*.kts")
            }
            tasks.named("check") { dependsOn(ktlintCheck) }
        }
    }
}
