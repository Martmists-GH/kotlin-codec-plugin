import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("kapt") version "2.2.21"
    `java-gradle-plugin`
}

group = "com.martmists.serialization"
version = "1.0.0"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

tasks {
    withType<KotlinCompile> {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_21
        }
    }
}

dependencies {
    // Compiler and Gradle APIs
    compileOnly(kotlin("compiler-embeddable", "2.2.21"))
    implementation(kotlin("gradle-plugin-api",  "2.2.21"))

    // AutoService for registration
    implementation("com.google.auto.service:auto-service:1.1.1")
    kapt("com.google.auto.service:auto-service:1.1.1")
}

gradlePlugin {
    plugins {
        create("serializationCodecPlugin") {
            id = "com.martmists.codecs"
            implementationClass = "com.martmists.serialization.CodecGradlePlugin"
            displayName = "Serialization Codec Plugin"
            description = "Adds CODEC field to classes annotated with @Record"
        }
    }
}
