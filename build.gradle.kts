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

        tasks.withType<Test>().configureEach {
            useJUnitPlatform {
                excludeTags("integration")
            }
        }

        tasks.register<Test>("integrationTest") {
            description = "Runs integration tests."
            group = "verification"
            useJUnitPlatform {
                includeTags("integration")
            }
            shouldRunAfter(tasks.named("test"))
        }
    }
}
