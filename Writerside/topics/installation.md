# Installation

Kamel is published to Maven Central under `es.jvbabi.overmail:kamel`.

## Requirements

| Component | Requirement |
|-----------|-------------|
| JVM | Java 26 (the library is compiled with `jvmTarget = 26`) |
| Kotlin | A version with `kotlin.time.Instant` in the standard library (2.1.20 or newer) |
| Coroutines | `kotlinx-coroutines-core` on your classpath |
| Logging | Optional: an SLF4J binding, for example Logback. See [](logging-and-debugging.md) |

## Add the dependency

<tabs>
<tab title="Version catalog">

Add the library to `gradle/libs.versions.toml`:

```toml
[versions]
kamel = "%version%"

[libraries]
kamel = { module = "es.jvbabi.overmail:kamel", version.ref = "kamel" }
```

Then reference it in your `build.gradle.kts`:

```kotlin
dependencies {
    implementation(libs.kamel)
}
```

</tab>
<tab title="build.gradle.kts">

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("es.jvbabi.overmail:kamel:%version%")
}
```

</tab>
<tab title="Maven">

```xml
<dependency>
    <groupId>es.jvbabi.overmail</groupId>
    <artifactId>kamel</artifactId>
    <version>%version%</version>
</dependency>
```

</tab>
</tabs>

> Check [Maven Central](https://central.sonatype.com/artifact/es.jvbabi.overmail/kamel) or the
> [GitHub tags](https://github.com/Overmail/kamel/tags) for the latest version.
{style="tip"}

## Coroutines {id="coroutines"}

Kamel's API is made of `suspend` functions, `Deferred` values and a `Flow`. Your code needs the coroutines library
to call them:

```kotlin
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}
```

## Next step

Continue with [](quick-start.md) to read your first messages.
