/*
 * Copyright Datadatdat.
 */

import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

apply(plugin="com.github.ben-manes.versions")

buildscript {
    repositories {
        mavenCentral()
        maven("https://plugins.gradle.org/m2/")
    }

    dependencies {
        classpath("com.github.ben-manes:gradle-versions-plugin:0.53.0")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.21")
    }
}

// Configuration for dependencyUpdates task to ignore release candidates
tasks.withType<DependencyUpdatesTask>().configureEach {
    resolutionStrategy {
        componentSelection {
            all {
                val rejected = listOf("alpha", "beta", "rc", "cr", "m", "preview", "b", "ea", "eap").any { qualifier ->
                    candidate.version.matches(Regex("(?i).*[.-]$qualifier[.\\d-+]*"))
                }
                if (rejected) {
                    reject("Release candidate")
                }
            }
        }
    }
}

subprojects {
    buildscript {
        repositories {
            mavenCentral()
            maven("https://plugins.gradle.org/m2/")
        }
    }

    val ktlint by configurations.creating

    dependencies {
        ktlint("com.pinterest.ktlint:ktlint-cli:1.8.0")
    }

    // Treat all warnings as errors
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
            allWarningsAsErrors.set(true)
        }
    }

    // Enable ktlint checks and formatting
    val ktlintTask = tasks.register<JavaExec>("ktlint") {
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        description = "Check Kotlin code style"
        classpath = ktlint
        mainClass.set("com.pinterest.ktlint.Main")
        args("src/**/*.kt")
    }

    tasks.register<JavaExec>("ktlintFormat") {
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        description = "Fix Kotlin code style deviations"
        classpath = ktlint
        mainClass.set("com.pinterest.ktlint.Main")
        args("-F", "src/**/*.kt")
    }

    // Run ktlint as part of the check task (if it exists)
    afterEvaluate {
        tasks.findByName("check")?.dependsOn(ktlintTask)
    }
}
