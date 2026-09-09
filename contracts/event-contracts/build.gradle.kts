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
        artifact = libs.protoc.get().toString()
    }
}

dependencies {
    api(libs.protobuf.java)

    testImplementation(libs.junit.jupiter)
}
