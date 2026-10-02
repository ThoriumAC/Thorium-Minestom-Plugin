import com.google.protobuf.gradle.proto

plugins {
    `java-library`
    id("com.gradleup.shadow") version "9.6.1"
    id("com.google.protobuf") version "0.10.0"
    `maven-publish`
}

val protobufVersion = "4.36.1"
val minestomVersion = "2026.05.17-1.21.11"

group = "ac.thorium"
// Suffixed with the Minecraft version, as Minestom's own versions are, so each branch publishes its own line.
version = "1.0.4-" + minestomVersion.substringAfter('-')

repositories {
    mavenCentral()
}

dependencies {
    compileOnly("net.minestom:minestom:$minestomVersion")
    compileOnly("it.unimi.dsi:fastutil:8.5.19") // ships with Minestom at runtime
    implementation("org.java-websocket:Java-WebSocket:1.6.0") { exclude(group = "org.slf4j") }
    implementation("com.google.protobuf:protobuf-java:$protobufVersion")

    testImplementation("net.minestom:minestom:$minestomVersion")
    testImplementation("net.minestom:testing:$minestomVersion")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:deprecation"))
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:$protobufVersion" }
}

sourceSets {
    main {
        proto { srcDir("proto") }
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
    archiveBaseName.set("thorium-minestom")
    mergeServiceFiles()
    val prefix = "ac.thorium.mc.libs"
    relocate("org.java_websocket", "$prefix.websocket")
    relocate("com.google.protobuf", "$prefix.protobuf")
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/9/module-info.class", "module-info.class", "**/*.proto", "META-INF/LICENSE.txt")
    from(files("LICENSE", "NOTICE")) { into("META-INF") }
    from("licenses") { into("META-INF/licenses") }
}

// A runnable Minestom server around the shipped plugin jar, booted by the Thorium-Mineflayer compat harness.
val harness by sourceSets.creating { compileClasspath += sourceSets.main.get().output }
dependencies { "harnessImplementation"("net.minestom:minestom:$minestomVersion") }

tasks.register<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("harnessJar") {
    archiveFileName.set("thorium-minestom-harness.jar")
    from(harness.output, tasks.shadowJar.map { zipTree(it.archiveFile) })
    configurations.set(listOf(project.configurations["harnessRuntimeClasspath"]))
    manifest { attributes["Main-Class"] = "ac.thorium.mc.harness.HarnessServer" }
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

tasks.jar { archiveClassifier.set("plain") }
tasks.build { dependsOn(tasks.shadowJar) }

tasks.test {
    useJUnitPlatform()
    systemProperty("minestom.inside-test", "true")
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

// The shaded jar is the artifact: websocket and protobuf are relocated inside it, so the POM
// carries no dependencies. Minestom itself is the consumer's. JitPack serves it from mavenLocal.
publishing {
    publications {
        create<MavenPublication>("maven") { from(components["shadow"]) }
    }
}
