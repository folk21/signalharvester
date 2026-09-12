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
    implementation("io.micronaut.flyway:micronaut-flyway")
    implementation("io.micronaut.data:micronaut-data-tx-jdbc")

    runtimeOnly("io.micronaut.sql:micronaut-jdbc-hikari")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    // The Micronaut-managed Netty client registry requires a JSON mapper in the application context.
    implementation("io.micronaut.serde:micronaut-serde-jackson")

    implementation("io.micronaut.validation:micronaut-validation")

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.testcontainers.postgresql)
}
