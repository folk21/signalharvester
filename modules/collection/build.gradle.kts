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
    implementation(project(":modules:configuration"))

    testImplementation(libs.junit.jupiter)
}

tasks.test {
    useJUnitPlatform()
}
