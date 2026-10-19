plugins {
    `kotlin-dsl`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(27))
    }
}

repositories {
    gradlePluginPortal()
}

dependencies {
    implementation("net.kyori", "indra-common", "4.1.0")
    implementation("net.kyori", "indra-git", "4.1.0")
    implementation("com.gradleup.shadow", "shadow-gradle-plugin", "9.6.1")
    implementation("gradle.plugin.org.jetbrains.gradle.plugin.idea-ext", "gradle-idea-ext", "1.4.1")
}
