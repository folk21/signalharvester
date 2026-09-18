plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}


dependencies {
    testImplementation(libs.junit.jupiter)
}
