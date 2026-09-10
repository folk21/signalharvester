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
    implementation(project(":common"))
    implementation(project(":contracts:event-contracts"))
    implementation(project(":modules:configuration"))

    implementation("io.micronaut:micronaut-context")
    implementation("io.micronaut:micronaut-http-client")

    // The Micronaut-managed Netty client registry requires a JSON mapper in the application context.
    implementation("io.micronaut.serde:micronaut-serde-jackson")

    implementation("io.micronaut.validation:micronaut-validation")
    annotationProcessor("io.micronaut.validation:micronaut-validation-processor")

    testImplementation(libs.junit.jupiter)
}
