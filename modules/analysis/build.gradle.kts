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
        annotations.add("io.signalharvester.analysis.*")
    }
}

dependencies {
    annotationProcessor("io.micronaut:micronaut-inject-java")
    annotationProcessor("io.micronaut.validation:micronaut-validation-processor")

    implementation(project(":contracts:event-contracts"))

    implementation("io.micronaut.kafka:micronaut-kafka")
    implementation("io.micronaut:micronaut-context")
    implementation("io.micronaut.flyway:micronaut-flyway")
    implementation("io.micronaut.data:micronaut-data-tx-jdbc")
    implementation("io.micronaut.validation:micronaut-validation")

    runtimeOnly("io.micronaut.sql:micronaut-jdbc-hikari")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.kafka)
}
