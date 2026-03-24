import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.7.1"
}

group = "com.nju"
version = "1.2.0"

repositories {
    mavenCentral()
    gradlePluginPortal()
    intellijPlatform {
        defaultRepositories()
    }
}

// Configure IntelliJ Platform Gradle Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
        create("IC", "2022.3")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        // Add necessary plugin dependencies for compilation here, example:
        bundledPlugin("com.intellij.java")
    }
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.15.2")

    compileOnly("org.projectlombok:lombok:1.18.30")
    annotationProcessor("org.projectlombok:lombok:1.18.30")

    testCompileOnly("org.projectlombok:lombok:1.18.30")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.30")

    runtimeOnly("org.slf4j:slf4j-simple:2.0.7")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "223"
        }
    }

    pluginVerification {
        ides {
            create("IC", "2022.3")
        }
    }

    signing {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishing {
        token.set(System.getenv("PUBLISH_TOKEN"))
        channels.set(listOf("stable"))
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
        options.encoding = "UTF-8"
    }

    withType<PrepareSandboxTask> {
        doLast {
            fun prepareSandboxNoiseReduction(configDir: File) {
                if (!configDir.exists()) {
                    configDir.mkdirs()
                }

                File(configDir, "early-access-registry.txt").writeText(
                    """
                    ide.experimental.ui
                    false
                    unknown.sdk
                    false
                    unknown.sdk.auto
                    false
                    sdk.detector.enabled
                    false
                    """.trimIndent() + System.lineSeparator(),
                    Charsets.UTF_8
                )

                val vmOptionsFile = File(configDir, "idea64.exe.vmoptions")
                if (!vmOptionsFile.exists()) {
                    vmOptionsFile.writeText("", Charsets.UTF_8)
                }
            }

            prepareSandboxNoiseReduction(sandboxConfigDirectory.get().asFile)
        }
    }

    withType<RunIdeTask> {
        // The sandbox IDE does not need native file watching for plugin debugging.
        systemProperty("idea.filewatcher.disabled", "true")

        doFirst {
            val vmOptionsFile = sandboxConfigDirectory.get().file("idea64.exe.vmoptions")
            systemProperty("jb.vmOptionsFile", vmOptionsFile.asFile.absolutePath)
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
