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
    implementation(project(":modules:security"))
    implementation(project(":modules:operations"))

    implementation("io.micronaut:micronaut-runtime")
    implementation("io.micronaut:micronaut-management")
    implementation("io.micronaut.micrometer:micronaut-micrometer-core")
    implementation("io.micronaut.micrometer:micronaut-micrometer-registry-prometheus")
    implementation("io.micronaut.tracing:micronaut-tracing-opentelemetry")
    implementation("io.micronaut.tracing:micronaut-tracing-opentelemetry-http")
    implementation("io.micronaut.tracing:micronaut-tracing-opentelemetry-jdbc")
    implementation("io.micronaut.tracing:micronaut-tracing-opentelemetry-kafka")

    runtimeOnly("io.opentelemetry:opentelemetry-exporter-otlp")
    runtimeOnly("io.opentelemetry.instrumentation:opentelemetry-logback-mdc-1.0")

    runtimeOnly("ch.qos.logback:logback-classic")

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.archunit.junit5)
}
