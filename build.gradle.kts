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
    implementation("com.knuddels:jtokkit:1.1.0")
    implementation(project(":ctok-java"))
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

// Ship project and dependency notices in both distributable JARs.
tasks.processResources {
    from("LICENSE") { into("META-INF/licenses/loc-history-visualizer") }
    from("licenses") { into("META-INF/licenses") }
}

tasks.register<Jar>("cliJar") {
    group = "build"
    description = "Builds the headless source metrics analyzer"
    dependsOn(tasks.classes, ":ctok-java:jar")
    archiveClassifier = "cli"
    from(sourceSets.main.get().output) {
        include("dev/lochistory/analysis/**", "dev/lochistory/model/**", "dev/lochistory/cli/**", "META-INF/licenses/**")
    }
    from(provider { configurations.runtimeClasspath.get().filter { it.name.startsWith("jtokkit-") || it.name.startsWith("ctok-java-") }.map { zipTree(it) } })
    manifest { attributes["Main-Class"] = "dev.lochistory.cli.LocHistoryCli" }
}

tasks.register<JavaExec>("runCli") {
    group = "application"
    description = "Runs the headless LOC analyzer; pass options with --args"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "dev.lochistory.cli.LocHistoryCli"
}
