import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort
import com.github.spotbugs.snom.SpotBugsExtension
import com.github.spotbugs.snom.SpotBugsTask
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    base
    jacoco
    alias(libs.plugins.spotbugs) apply false
    alias(libs.plugins.dependency.analysis)
}

allprojects {
    group = "io.signalharvester"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

val jacocoAggregateReport = tasks.register<JacocoReport>("jacocoAggregateReport") {
    description = "Generates aggregate unit and integration test coverage for handwritten production Java code."
    group = "verification"

    reports {
        html.required.set(true)
        xml.required.set(true)
        csv.required.set(false)
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/aggregate/html"))
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/aggregate/jacoco.xml"))
    }
}

subprojects {
    plugins.withType<JavaPlugin> {
        dependencies {
            add("testRuntimeOnly", libs.junit.platform.launcher)
        }

        val sourceSets = extensions.getByType<SourceSetContainer>()
        val mainSourceSet = sourceSets.named("main")
        val integrationTestSourceSet = sourceSets.create("integrationTest") {
            compileClasspath += mainSourceSet.get().output
            runtimeClasspath += mainSourceSet.get().output
        }

        configurations.named(integrationTestSourceSet.implementationConfigurationName) {
            extendsFrom(configurations.getByName("testImplementation"))
        }
        configurations.named(integrationTestSourceSet.compileOnlyConfigurationName) {
            extendsFrom(configurations.getByName("testCompileOnly"))
        }
        configurations.named(integrationTestSourceSet.runtimeOnlyConfigurationName) {
            extendsFrom(configurations.getByName("testRuntimeOnly"))
        }
        configurations.named(integrationTestSourceSet.annotationProcessorConfigurationName) {
            extendsFrom(configurations.getByName("testAnnotationProcessor"))
        }

        tasks.named<Test>("test") {
            useJUnitPlatform()
        }

        tasks.register<Test>("integrationTest") {
            description = "Runs integration tests from the integrationTest source set."
            group = "verification"
            testClassesDirs = integrationTestSourceSet.output.classesDirs
            classpath = integrationTestSourceSet.runtimeClasspath
            useJUnitPlatform()
            shouldRunAfter(tasks.named("test"))
        }

        apply(plugin = "jacoco")
        extensions.configure<JacocoPluginExtension> {
            toolVersion = libs.versions.jacoco.get()
        }

        val jacocoExecutionData = fileTree(layout.buildDirectory.dir("jacoco")) {
            include("*.exec")
        }
        rootProject.tasks.named<JacocoReport>("jacocoAggregateReport") {
            dependsOn(tasks.named("test"), tasks.named("integrationTest"))
            executionData.from(jacocoExecutionData)
        }

        val handwrittenMainDir = file("src/main/java")
        val hasHandwrittenMainSources = handwrittenMainDir.exists() &&
            handwrittenMainDir.walkTopDown().any { it.isFile && it.extension == "java" }
        val isProductionProject = !path.startsWith(":testing")

        if (isProductionProject && hasHandwrittenMainSources) {
            apply(plugin = "com.github.spotbugs")

            extensions.configure<SpotBugsExtension> {
                ignoreFailures.set(true)
                showStackTraces.set(true)
                showProgress.set(true)
                effort.set(Effort.DEFAULT)
                reportLevel.set(Confidence.DEFAULT)
                runOnCheck.set(false)
            }

            tasks.withType<SpotBugsTask>().configureEach {
                if (name != "spotbugsMain") {
                    enabled = false
                } else {
                    reports {
                        create("html") {
                            required.set(true)
                            outputLocation.set(layout.buildDirectory.file("reports/spotbugs/main.html"))
                            setStylesheet("fancy-hist.xsl")
                        }
                        create("xml") {
                            required.set(true)
                            outputLocation.set(layout.buildDirectory.file("reports/spotbugs/main.xml"))
                        }
                    }
                }
            }

            val coverageClassTree = mainSourceSet.map { sourceSet ->
                sourceSet.output.classesDirs.asFileTree.matching {
                    exclude(
                        "**/*\$Definition*.class",
                        "**/*\$Introspection*.class",
                        "**/*\$Intercepted*.class",
                        "**/*\$Reference.class",
                    )
                }
            }
            rootProject.tasks.named<JacocoReport>("jacocoAggregateReport") {
                sourceDirectories.from(handwrittenMainDir)
                classDirectories.from(coverageClassTree)
            }
        }

        apply(plugin = "com.autonomousapps.dependency-analysis")
    }
}
