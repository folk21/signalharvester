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
        annotations.add("io.signalharvester.eventobservation.*")
    }
}

dependencies {
    implementation(project(":common"))
    annotationProcessor("io.micronaut:micronaut-inject-java")
    annotationProcessor("io.micronaut.serde:micronaut-serde-processor")
    annotationProcessor("io.micronaut.validation:micronaut-validation-processor")
    testAnnotationProcessor("io.micronaut:micronaut-inject-java")
    testAnnotationProcessor("io.micronaut.validation:micronaut-validation-processor")

    implementation(project(":contracts:event-contracts"))

    implementation("io.micronaut.kafka:micronaut-kafka")
    implementation("io.micronaut:micronaut-http-server")
    implementation("io.micronaut.serde:micronaut-serde-jackson")
    implementation("io.micronaut.validation:micronaut-validation")
    implementation("io.micronaut:micronaut-context")
    implementation("io.micronaut.flyway:micronaut-flyway")
    implementation("io.micronaut.data:micronaut-data-tx-jdbc")
    implementation("io.micronaut.sql:micronaut-jdbi")

    runtimeOnly("io.micronaut.sql:micronaut-jdbc-hikari")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("io.micronaut:micronaut-http-server-netty")
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.kafka)
}
