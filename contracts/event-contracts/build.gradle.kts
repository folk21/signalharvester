plugins {
    `java-library`
    alias(libs.plugins.protobuf)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }
}

dependencies {
    api(libs.protobuf.java)

    testImplementation(libs.junit.jupiter)
}
