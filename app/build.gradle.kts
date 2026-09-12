plugins {
    alias(libs.plugins.micronaut.application)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

micronaut {
    runtime("netty")

    processing {
        incremental.set(true)
        annotations.add("io.signalharvester.*")
    }
}

application {
    mainClass.set("io.signalharvester.Application")
}

dependencies {
    annotationProcessor("io.micronaut:micronaut-inject-java")

    implementation(project(":modules:configuration"))
    implementation(project(":modules:collection"))
    implementation(project(":modules:analysis"))
    implementation(project(":modules:results"))
    implementation(project(":modules:event-observation"))

    implementation("io.micronaut:micronaut-runtime")

    runtimeOnly("ch.qos.logback:logback-classic")

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.archunit.junit5)
}
