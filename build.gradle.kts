plugins {
    id("java")
}

group = "org.vttale.vttale"
version = "1.0-SNAPSHOT"

subprojects {
    plugins.apply("java")

    group = "${project.property("group")}"
    version = "${project.property("version")}"

    repositories {
        mavenCentral()
    }

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
        withSourcesJar()
    }

    tasks {
        withType<JavaCompile> {
            options.encoding = "UTF-8"
            options.release = 21
            options.compilerArgs.add("-Xlint:none")
        }
        jar {
            archiveClassifier.set("noshade")
            archiveFileName.set("${project.property("artifactName")}-${project.version}.jar")
        }
    }
}