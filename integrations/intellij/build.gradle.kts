plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.20.0"
}

group = "com.usebruno"
version = "0.4.9"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(if (providers.gradleProperty("brunoIdePath").isPresent) 25 else 21))
    }
}

intellijPlatform {
    pluginConfiguration {
        name = "Bruno"
        version = project.version.toString()
        description = "Discover Java Spring controller APIs, send requests with headers and named environments, and run Bruno flows."
        changeNotes = "Adds a module/controller tree, independent persistent request drafts, responsive navigation, structured parameter and body editors, environment secrets, request authentication, response views, and scoped transport settings."
        ideaVersion {
            sinceBuild = "243"
        }
    }
}

dependencies {
    intellijPlatform {
        val localIde = providers.gradleProperty("brunoIdePath").orNull
        if (localIde != null) local(localIde) else intellijIdeaCommunity("2024.3")
        bundledPlugin("com.intellij.java")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Plugin.Java)
    }
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
}

tasks.test {
    providers.gradleProperty("brunoIdePath").orNull?.let { executable = "$it/jbr/bin/java" }
    if (providers.gradleProperty("brunoGuiTests").isPresent) {
        systemProperty("java.awt.headless", "false")
        systemProperty("bruno.guiTests", "true")
    }
    systemProperty("bruno.screenshotDir", layout.buildDirectory.dir("ui-snapshots").get().asFile.absolutePath)
}

tasks.processResources {
    dependsOn(":spring-bridge:jar", ":spring-bridge:generatePomFileForBridgePublication")
    from("spring-bridge/build/libs") { include("bruno-spring-bridge-${project.version}.jar"); rename { "bruno-spring-bridge.jar" }; into("bridge") }
    from("spring-bridge/build/publications/bridge/pom-default.xml") { rename { "bruno-spring-bridge.pom" }; into("bridge") }
}
