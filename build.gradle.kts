import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.6.20"
    application
}

group = "org.doubleopen"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

application {
    applicationName = "do-convert"
    mainClass.set("org.doubleopen.convert.DoConvertKt")
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("com.github.oss-review-toolkit.ort:spdx-utils:11728a9b80")
    implementation("com.github.oss-review-toolkit.ort:model:11728a9b80")
    implementation("com.github.ajalt.clikt:clikt:3.4.2")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "11"
}