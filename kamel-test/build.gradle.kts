import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.maven.publish)
}

group = "es.jvbabi.overmail"
// Published together with kamel, under the same version.
version = rootProject.version

repositories {
    mavenCentral()
}

dependencies {
    // api: a test that builds a folder in memory talks about MailFolder, Email and Flow.
    api(project(":"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(26)

    compilerOptions {
        jvmTarget = JvmTarget.JVM_26
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates(project.group.toString(), project.name, project.version.toString())

    pom {
        name = "Kamel Test"
        description = "In-memory mailboxes for testing code that uses Kamel"
        url = "https://github.com/Overmail/kamel"
        inceptionYear = "2026"
        licenses {
            license {
                name = "GPL-3.0"
                url = "https://www.gnu.org/licenses/gpl-3.0.en.html"
            }
        }

        developers {
            developer {
                id = "julius-vincent-babies"
                name = "Julius Vincent Babies"
                email = "julvin.babies@gmail.com"
                url = "https://github.com/Julius-Babies"
            }
        }

        scm {
            url = "https://github.com/Overmail/kamel"
            connection = "scm:git:git://github.com/Overmail/kamel.git"
            developerConnection = "scm:git:ssh://git@github.com/Overmail/kamel.git"
        }
    }
}
