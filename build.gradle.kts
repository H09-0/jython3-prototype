plugins {
    java
    application
    antlr
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    antlr("org.antlr:antlr4:4.13.1")
    implementation("org.antlr:antlr4-runtime:4.13.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.0")
}

application {
    mainClass.set("org.example.jython3.Jython3Interpreter")
}

tasks.register<JavaExec>("runScript") {
    group = "application"
    description = "Run a Python script file"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.example.jython3.RunScript")
    args = listOf("test.py")
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
