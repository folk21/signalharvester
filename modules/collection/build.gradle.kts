plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    implementation(project(":common"))
    implementation(project(":contracts:event-contracts"))
}

dependencies {
    implementation(project(":modules:configuration"))
}
