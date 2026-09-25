plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    implementation(libs.testcontainers.kafka)
    implementation(libs.testcontainers.postgresql)
    api(libs.awaitility)
    testImplementation(libs.junit.jupiter)
}
