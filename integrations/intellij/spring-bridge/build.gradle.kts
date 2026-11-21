plugins { `java-library`; `maven-publish` }
group = "com.usebruno"
version = rootProject.version
repositories { mavenLocal(); mavenCentral() }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }; withSourcesJar() }
base { archivesName.set("bruno-spring-bridge") }
dependencies {
    api("org.springframework.boot:spring-boot-autoconfigure:3.4.0")
    implementation("org.springframework:spring-webmvc:6.2.0")
    implementation("org.springframework:spring-test:6.2.0")
    implementation("com.google.code.gson:gson:2.11.0")
    compileOnly("jakarta.servlet:jakarta.servlet-api:6.0.0")
    testImplementation("jakarta.servlet:jakarta.servlet-api:6.0.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.xuxueli:xxl-job-core:2.4.1")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.18.0")
}
publishing { publications { create<MavenPublication>("bridge") { artifactId = "bruno-spring-bridge"; from(components["java"]) } } }
tasks.withType<JavaCompile>().configureEach { options.release.set(21); options.encoding = "UTF-8" }

// Run the same baseline-compiled bridge against the app's newer Spring runtime.
if (providers.gradleProperty("bridgeModern").isPresent) {
    dependencies { testRuntimeOnly("tools.jackson.core:jackson-databind:3.1.5") }
    configurations.testRuntimeClasspath {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.springframework" && requested.name != "spring-jcl") useVersion("7.0.9")
            if (requested.group == "org.springframework.boot") useVersion("4.1.1")
            if (requested.group == "jakarta.servlet") useVersion("6.1.0")
        }
    }
}
