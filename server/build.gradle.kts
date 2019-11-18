/*
 * Copyright The Titan Project Contributors.
 */

plugins {
    kotlin("jvm")
    jacoco
    "com.github.ben-manes.versions"
    `maven-publish`

}
repositories {
    mavenLocal()
    mavenCentral()
    jcenter()
    maven("https://dl.bintray.com/kotlin/kotlinx")
    maven {
        name = "titan"
        url = uri("https://maven.titan-data.io")
    }
}

dependencies {
    compile(kotlin("stdlib"))
    compile("io.titandata:remote-sdk:0.0.11")
    compile("com.google.code.gson:gson:2.8.6")
    compile("com.amazonaws:aws-java-sdk-s3:1.11.668")
    compile("javax.xml.bind:jaxb-api:2.3.1")
    testImplementation("io.kotlintest:kotlintest-runner-junit5:3.4.2")
    testImplementation("io.mockk:mockk:1.9.3")
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
    false -> "titan-data-maven"
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "io.titandata"
            artifactId = "s3-remote-server"

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

// Test configuration

tasks.test {
    useJUnitPlatform()
}

