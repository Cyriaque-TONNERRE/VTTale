import org.gradle.api.tasks.testing.logging.TestExceptionFormat

// Shared config only; platform:hytale applies hytale-tools itself (see its build.gradle.kts).
allprojects {
    group = property("group").toString()
    version = property("version").toString()
}

subprojects {
    plugins.withId("java") {
        // `java { }` type-safe accessors are only generated for scripts that apply the plugin,
        // so configure the extension by type instead (same effect).
        val javaVersion = property("java_version").toString().toInt()
        extensions.configure<JavaPluginExtension>("java") {
            toolchain.languageVersion.set(JavaLanguageVersion.of(javaVersion))
        }
        repositories {
            mavenCentral()
        }
        tasks.withType<JavaCompile>().configureEach {
            // sources are UTF-8; never fall back to the platform charset
            options.encoding = "UTF-8"
        }
        tasks.withType<Javadoc>().configureEach {
            (options as StandardJavadocDocletOptions)
                .addStringOption("Xdoclint:-missing", "-quiet")
        }

        // ---- Tests: JUnit 5 for every Java subproject, configured in one place ----
        dependencies {
            val junitVersion = property("junit_version").toString()
            "testImplementation"(platform("org.junit:junit-bom:$junitVersion"))
            "testImplementation"("org.junit.jupiter:junit-jupiter")
            "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            // A silent test suite is a suite nobody reads: always show what ran and why it failed.
            testLogging {
                events("passed", "skipped", "failed")
                exceptionFormat = TestExceptionFormat.FULL
                showStandardStreams = false
            }
        }
    }
}
