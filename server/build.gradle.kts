/*
 * Copyright Datadatdat.
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
        name = "datadatdat"
        url = uri("https://datadatdat-maven.s3.amazonaws.com")
    }
}

dependencies {
	implementation(kotlin("stdlib"))
	implementation("com.datadatdat:remote-sdk:1.5.0")
	implementation("com.google.code.gson:gson:2.13.2")
	implementation("com.amazonaws:aws-java-sdk-s3:1.12.794")
	implementation("javax.xml.bind:jaxb-api:2.3.1")
	testImplementation("io.kotlintest:kotlintest-runner-junit5:3.4.2")
	testImplementation("io.mockk:mockk:1.14.7")
}// Jar configuration
group = "com.datadatdat"
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
            groupId = "com.datadatdat"
            artifactId = "s3-remote-server"

            from(components["java"])
        }
    }

    repositories {
        maven {
            name = "datadatdat"
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

