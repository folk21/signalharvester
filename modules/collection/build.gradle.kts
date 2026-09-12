plugins {
    alias(libs.plugins.micronaut.library)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

micronaut {
    processing {
        incremental.set(true)
        annotations.add("io.signalharvester.collection.*")
    }
}

dependencies {
    annotationProcessor("io.micronaut:micronaut-inject-java")
    annotationProcessor("io.micronaut.serde:micronaut-serde-processor")
    annotationProcessor("io.micronaut.validation:micronaut-validation-processor")
    api(project(":modules:configuration"))
    implementation(project(":contracts:event-contracts"))

    implementation("io.micronaut.kafka:micronaut-kafka")
    implementation("io.micronaut:micronaut-http-server")

    implementation("io.micronaut:micronaut-context")
    implementation("io.micronaut:micronaut-http-client")

    // The Micronaut-managed Netty client registry requires a JSON mapper in the application context.
    implementation("io.micronaut.serde:micronaut-serde-jackson")

    implementation("io.micronaut.validation:micronaut-validation")

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.testcontainers.postgresql)
}
