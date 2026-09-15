plugins {
    java
}

val micronautVersion: String by project

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    testImplementation(project(":app"))
    testImplementation(project(":modules:configuration"))
    testImplementation(project(":modules:collection"))
    testImplementation(project(":modules:analysis"))
    testImplementation(project(":modules:results"))
    testImplementation(project(":modules:security"))
    testImplementation(project(":contracts:event-contracts"))
    testImplementation(project(":testing:test-support"))

    testImplementation(platform("io.micronaut.platform:micronaut-platform:$micronautVersion"))
    testImplementation("io.micronaut:micronaut-context")
    testImplementation("io.micronaut:micronaut-runtime")
    testImplementation("io.micronaut.kafka:micronaut-kafka")
    testRuntimeOnly("io.micronaut:micronaut-http-server-netty")

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.kafka)
}
