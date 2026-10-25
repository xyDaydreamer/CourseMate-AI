import java.util.Properties
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "edu.t07.coursemate"
version = "0.4.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Machine-specific paths stay out of the shared build configuration.
val localSettings = Properties().apply {
    val settingsFile = file("local.properties")
    if (settingsFile.exists()) settingsFile.inputStream().use { load(it) }
}
val localIdeaPath = providers.gradleProperty("ideaLocalPath")
    .orElse(provider { localSettings.getProperty("ideaLocalPath", "") })

dependencies {
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("org.apache.pdfbox:pdfbox:3.0.8")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
    intellijPlatform {
        if (localIdeaPath.get().isNotBlank()) {
            local(localIdeaPath.get())
        } else {
            intellijIdea("2026.2.3")
        }
        testFramework(TestFrameworkType.Platform)
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.test {
    useJUnitPlatform()
    // Only this plugin and its required platform modules belong in the headless test application.
    systemProperty("idea.load.plugins.id", "edu.t07.coursemate.ai")
    // Unit tests must never receive a real developer credential.
    environment.remove("DEEPSEEK_API_KEY")
    systemProperty("coursemate.testCourseDir", layout.projectDirectory.dir("course_files").asFile.absolutePath)
}

tasks.named<JavaExec>("runIde") {
    systemProperty("coursemate.courseDir", layout.projectDirectory.dir("course_files").asFile.absolutePath)
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild.set("262.10968.63")
            untilBuild.set("262.*")
        }
    }
}

tasks.wrapper {
    gradleVersion = "9.1.0"
    distributionType = Wrapper.DistributionType.BIN
    distributionUrl = "https://downloads.gradle.org/distributions/gradle-9.1.0-bin.zip"
    distributionSha256Sum = "a17ddd85a26b6a7f5ddb71ff8b05fc5104c0202c6e64782429790c933686c806"
}
