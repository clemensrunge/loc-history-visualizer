plugins {
    `java-library`
    `maven-publish`
}
group = "dev.ctok"
version = "1.3.0-java.1"
repositories { mavenCentral() }
java {
    withSourcesJar()
    withJavadocJar()
}
tasks.withType<JavaCompile>().configureEach { options.release = 21; options.encoding = "UTF-8" }
tasks.javadoc { options.encoding = "UTF-8" }
tasks.jar { manifest { attributes["Main-Class"] = "dev.ctok.CtokCli"; attributes["Automatic-Module-Name"] = "dev.ctok" } }
tasks.processResources {
    from(listOf("LICENSE", "NOTICE", "PROVENANCE.json")) { into("META-INF/licenses/ctok") }
}
// Source and documentation archives do not inherit processResources output.
for (archive in listOf("sourcesJar", "javadocJar")) {
    tasks.named<Jar>(archive) {
        from(listOf("LICENSE", "NOTICE", "PROVENANCE.json")) { into("META-INF/licenses/ctok") }
    }
}
publishing {
    publications {
        create<MavenPublication>("library") {
            from(components["java"])
            pom {
                name = "ctok-java"
                description = "Java 21 port of ctok for offline Claude token counting"
                url = "https://github.com/clemensrunge/ctok-java"
                licenses {
                    license {
                        name = "MIT License"
                        url = "https://github.com/clemensrunge/ctok-java/blob/main/LICENSE"
                        distribution = "repo"
                    }
                }
                developers {
                    developer { id = "sanderland"; name = "Sander Land"; roles.add("upstream author") }
                    developer { id = "clemensrunge"; name = "Clemens Runge"; roles.add("Java port maintainer") }
                }
                scm {
                    url = "https://github.com/clemensrunge/ctok-java"
                    connection = "scm:git:https://github.com/clemensrunge/ctok-java.git"
                    developerConnection = "scm:git:ssh://git@github.com/clemensrunge/ctok-java.git"
                }
            }
        }
    }
}
val verifyParity by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Run API checks and optional Python-reference parity corpus (-PparityFile=...)"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "dev.ctok.ParityTest"
    providers.gradleProperty("parityFile").orNull?.let { args(it) }
    maxHeapSize = "1g"
}
// Verification is an executable test harness, with no runtime or test-framework dependencies.
tasks.test {
    dependsOn(verifyParity)
    failOnNoDiscoveredTests = false
}
