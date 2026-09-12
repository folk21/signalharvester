plugins {
    base
}

allprojects {
    group = "io.signalharvester"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    plugins.withType<JavaPlugin> {
        dependencies {
            add("testRuntimeOnly", libs.junit.platform.launcher)
        }

        val testSourceSet = extensions
            .getByType<org.gradle.api.plugins.JavaPluginExtension>()
            .sourceSets
            .named("test")

        tasks.named<Test>("test") {
            useJUnitPlatform {
                excludeTags("integration")
            }
        }

        tasks.register<Test>("integrationTest") {
            description = "Runs integration tests."
            group = "verification"
            testClassesDirs = testSourceSet.get().output.classesDirs
            classpath = testSourceSet.get().runtimeClasspath
            useJUnitPlatform {
                includeTags("integration")
            }
            shouldRunAfter(tasks.named("test"))
        }
    }
}
