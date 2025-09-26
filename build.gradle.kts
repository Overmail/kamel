plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.maven.publish)
}

group = "com.overmail"
version = System.getenv("VERSION")?.ifBlank { null } ?: "unspecified"

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.kotlinx.datetime)
    implementation(libs.ktor.network)
    implementation(libs.ktor.network.tls)
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(24)

    compilerOptions {
        freeCompilerArgs.add("-Xopt-in=kotlin.time.ExperimentalTime")
        freeCompilerArgs.add("-Xopt-in=kotlin.contracts.ExperimentalContracts")
    }
}

val sourcesJar by tasks.registering(Jar::class) {
    archiveClassifier.set("sources")
    from(sourceSets.main.get().allSource)
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates(project.group.toString(), project.name, project.version.toString())

    pom {
        name = "Kamel"
        description = "Kotlin Asynchronous Mail Library"
        url = "https://github.com/Overmail/kamel"
        inceptionYear = "2025"
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