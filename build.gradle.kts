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
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
    }
}
