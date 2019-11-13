import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

buildscript {
    repositories {
        mavenCentral()
        maven("https://plugins.gradle.org/m2/")
    }

    dependencies {
        classpath("com.github.ben-manes:gradle-versions-plugin:0.27.0")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.3.50")
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
        ktlint("com.pinterest:ktlint:0.35.0")
    }

    // Treat all warnings as errors
    tasks.withType<KotlinCompile>().configureEach {
        kotlinOptions {
            jvmTarget = "1.8"
            allWarningsAsErrors = true
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

    // Enable ktlint checks and formatting
    val ktlintTask = tasks.register<JavaExec>("ktlint") {
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        description = "Check Kotlin code style"
        classpath = ktlint
        main = "com.pinterest.ktlint.Main"
        args("src/**/*.kt")
    }

    tasks.register<JavaExec>("ktlintFormat") {
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        description = "Fix Kotlin code style deviations"
        classpath = ktlint
        main = "com.pinterest.ktlint.Main"
        args("-F", "src/**/*.kt")
    }
}
