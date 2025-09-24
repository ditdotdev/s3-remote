/*
 * Copyright The Titan Project Contributors.
 */

plugins {
    kotlin("jvm")
    jacoco
    `maven-publish`
}

repositories {
    mavenLocal()
    mavenCentral()
    maven("https://dl.bintray.com/kotlin/kotlinx")
    maven {
        name = "titan"
        url = uri("https://datadatdat-maven.s3.amazonaws.com")
    }
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("io.titandata:remote-sdk:0.2.1")
    implementation("software.amazon.awssdk:auth:2.34.2")
    testImplementation("io.mockk:mockk:1.14.5")
    testImplementation("io.kotlintest:kotlintest-runner-junit5:3.4.2")
}

// Jar configuration
group = "io.titandata"
version = when(project.hasProperty("version")) {
    true -> project.property("version")!!
    false -> "latest"
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

val jar by tasks.getting(Jar::class) {
    archiveBaseName.set("s3-remote")
}

// Maven publishing configuration
val mavenBucket = when(project.hasProperty("mavenBucket")) {
    true -> project.property("mavenBucket")
    false -> "datadatdat-maven"
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "io.titandata"
            artifactId = "s3-remote-client"

            from(components["java"])
        }
    }

    repositories {
        maven {
            name = "titan"
            url = uri("s3://$mavenBucket")
            authentication {
                create<AwsImAuthentication>("awsIm")
            }
        }
    }
}

// ktlint configuration
tasks.named("check").get().dependsOn(tasks.named("ktlint"))

// Test configuration

tasks.test {
    useJUnitPlatform()
}

