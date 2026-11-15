plugins {
    java
    application
    antlr
    `maven-publish`
}

group = "io.github.h09-0"
version = "1.0.0-beta"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withSourcesJar()
}

tasks.withType<org.gradle.api.tasks.bundling.Jar>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

dependencies {
    antlr("org.antlr:antlr4:4.13.1")
    implementation("org.antlr:antlr4-runtime:4.13.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.0")
}

application {
    mainClass.set("org.example.jython3.cli.Repl")
}

tasks.register<JavaExec>("benchmark") {
    group = "application"
    description = "Run Jython 3 performance benchmark suite"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.example.jython3.benchmark.BenchmarkSuite")
}

tasks.register<JavaExec>("fieldTest") {
    group = "verification"
    description = "Run Jython 3 real-world field tests & resource/leak profiler"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.example.jython3.field.FieldTestRunner")
}

tasks.register<JavaExec>("runScript") {
    group = "application"
    description = "Run a Python script file"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.example.jython3.cli.RunScript")
    val scriptArg = if (project.hasProperty("args")) project.property("args").toString() else "test.py"
    args = listOf(scriptArg)
}

tasks.register<JavaExec>("repl") {
    group = "application"
    description = "Start Jython3 REPL"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.example.jython3.cli.Repl")
    standardInput = System.`in`
}

tasks.generateGrammarSource {
    arguments = arguments + listOf(
        "-visitor", "-long-messages",
        "-package", "org.example.jython3"
    )
    outputDirectory = file("${layout.buildDirectory.get()}/generated-src/antlr/main")
}

sourceSets["main"].java.srcDir("${layout.buildDirectory.get()}/generated-src/antlr/main")

tasks.compileJava {
    dependsOn(tasks.generateGrammarSource)
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<Test>("securityTest") {
    group = "verification"
    description = "Run dedicated security verification suite (OWASP/CWE/CERT/OpenSSF)"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("security")
    }
}

tasks.register<Test>("logicTest") {
    group = "verification"
    description = "Run dedicated language, builtins, stdlib, OOP, async, and bridge logic suite"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("logic")
    }
}

tasks.register<Sync>("installLocal") {
    group = "distribution"
    description = "Build distribution and update ~/.local/share/jython3 on the local system"
    dependsOn(tasks.installDist)
    from("${layout.buildDirectory.get()}/install/${project.name}")
    into("${System.getProperty("user.home")}/.local/share/jython3")
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            artifactId = "jython3"
            from(components["java"])
        }
    }
}
