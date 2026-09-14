plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        clion(providers.gradleProperty("platformVersion"))
        bundledPlugin("com.intellij.clion")
    }
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("junit:junit:4.13.2")
}

java {
    // Use the developer's current full JDK while emitting Java 21-compatible classes.
    toolchain { languageVersion = JavaLanguageVersion.of(JavaVersion.current().majorVersion.toInt()) }
}

tasks.withType<JavaCompile>().configureEach { options.release = 21 }

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "253"
            untilBuild = provider { null }
        }
    }
}

tasks.test { useJUnitPlatform() }

tasks.register<Jar>("cliJar") {
    group = "build"
    description = "Builds the dependency-free headless LOC analyzer"
    dependsOn(tasks.classes)
    archiveClassifier = "cli"
    from(sourceSets.main.get().output) {
        include("dev/lochistory/analysis/**", "dev/lochistory/model/**", "dev/lochistory/cli/**")
    }
    manifest { attributes["Main-Class"] = "dev.lochistory.cli.LocHistoryCli" }
}

tasks.register<JavaExec>("runCli") {
    group = "application"
    description = "Runs the headless LOC analyzer; pass options with --args"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "dev.lochistory.cli.LocHistoryCli"
}
