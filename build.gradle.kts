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
            (options as org.gradle.external.javadoc.StandardJavadocDocletOptions)
                .addStringOption("Xdoclint:-missing", "-quiet")
        }
    }
}
