plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "com.overmail"
version = "1.0-SNAPSHOT"

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