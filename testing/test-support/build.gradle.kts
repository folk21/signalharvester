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
    testImplementation(libs.junit.jupiter)
}
