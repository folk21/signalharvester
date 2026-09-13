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

        val sourceSets = extensions.getByType<org.gradle.api.tasks.SourceSetContainer>()
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
    }
}
