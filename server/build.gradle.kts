/*
 * Copyright Dit.
 */

plugins {
    kotlin("jvm")
    jacoco
    `maven-publish`
}
repositories {
    mavenCentral()
    maven("https://dl.bintray.com/kotlin/kotlinx")
    maven {
        name = "dit"
        url = uri("https://dit-maven.s3.amazonaws.com")
    }
}

dependencies {
	implementation(kotlin("stdlib"))
	implementation("dev.dit:remote-sdk:1.9.3")
	implementation("com.google.code.gson:gson:2.14.0")
	implementation("software.amazon.awssdk:s3:2.46.3")
	testImplementation("io.kotlintest:kotlintest-runner-junit5:3.4.2")
	testImplementation("io.mockk:mockk:1.14.11")
}// Jar configuration
group = "dev.dit"
version = when(project.hasProperty("version")) {
    true -> project.property("version")!!
    false -> "latest"
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

val jar by tasks.getting(Jar::class) {
    archiveBaseName.set("s3-remote")
}

// Maven publishing configuration
val mavenBucket = when(project.hasProperty("mavenBucket")) {
    true -> project.property("mavenBucket")
    false -> "dit-maven"
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "dev.dit"
            artifactId = "s3-remote-server"

            from(components["java"])
        }
    }

    repositories {
        maven {
            name = "dit"
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
    jvmArgs(
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
        "--add-opens", "java.base/java.util=ALL-UNNAMED",
        "--add-opens", "java.base/java.io=ALL-UNNAMED",
        "--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens", "java.base/java.nio.file=ALL-UNNAMED",
        "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
        "--add-opens", "java.base/java.net=ALL-UNNAMED"
    )
}

tasks.jacocoTestReport {
    reports {
        csv.required.set(true)
        xml.required.set(true)
        html.required.set(true)
    }
}

