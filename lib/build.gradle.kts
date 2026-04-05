import com.google.protobuf.gradle.*

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.protobuf)
    alias(libs.plugins.sqldelight)
    `java-library`
    `maven-publish`
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "com.obscura"
            artifactId = "obscura-kit"
            version = "0.1.0"
            from(components["java"])
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Protobuf
    implementation(libs.protobuf.kotlin)
    implementation(libs.protobuf.java)

    // SQLDelight
    implementation(libs.sqldelight.jvm)
    implementation(libs.sqldelight.runtime)
    implementation(libs.sqldelight.coroutines)

    // Signal Protocol
    implementation(libs.libsignal)

    // Networking
    implementation(libs.okhttp)

    // JSON
    implementation(libs.json)

    // Serialization (typed ORM models)
    implementation(libs.serialization.json)

    // Coroutines
    implementation(libs.coroutines.core)

    // Testing
    testImplementation(libs.junit.api)
    testRuntimeOnly(libs.junit.engine)
    testImplementation(libs.coroutines.test)
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.3"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                id("kotlin")
            }
        }
    }
}

sqldelight {
    databases {
        create("ObscuraDatabase") {
            packageName.set("com.obscura.kit.db")
            dialect("app.cash.sqldelight:sqlite-3-38-dialect:2.0.2")
        }
    }
}

sourceSets {
    main {
        proto {
            srcDir("../fixtures")
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
