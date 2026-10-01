// Lets Gradle download the Java 25 toolchain on machines that lack it, such as JitPack's builders.
plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

rootProject.name = "thorium-minestom"
