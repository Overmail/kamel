# kamel

[![Maven Central](https://img.shields.io/maven-central/v/es.jvbabi.overmail/kamel?label=Maven%20Central)](https://central.sonatype.com/artifact/es.jvbabi.overmail/kamel)

The IMAP library for Kotlin. Coroutine-based, built on Ktor sockets.

## Requirements

- JVM 26 (the library is compiled with `jvmTarget = 26`)
- Kotlin with `kotlinx-coroutines`
- An SLF4J binding at runtime (e.g. `ch.qos.logback:logback-classic`) if you want log output

## Installation

Gradle (Kotlin DSL):

```kotlin
dependencies {
    implementation("es.jvbabi.overmail:kamel:0.4.0")
}
```

Gradle version catalog (`gradle/libs.versions.toml`):

```toml
[versions]
kamel = "0.4.0"

[libraries]
kamel = { module = "es.jvbabi.overmail:kamel", version.ref = "kamel" }
```

Maven:

```xml
<dependency>
    <groupId>es.jvbabi.overmail</groupId>
    <artifactId>kamel</artifactId>
    <version>0.4.0</version>
</dependency>
```

## Getting started

Connect, pick a folder, fetch messages:

```kotlin
import es.jvbabi.overmail.core.ImapClient
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    ImapClient(
        host = "imap.example.com",
        port = 993,
        ssl = true,
        username = System.getenv("IMAP_USERNAME"),
        password = System.getenv("IMAP_PASSWORD"),
    ).use { client ->
        val inbox = client.getFolders().first { it.fullName == "INBOX" }

        val mails = inbox.getMails {
            getAll()      // or getId(42) / getUid(15201) / getIds(listOf(1, 2, 3))
            envelope = true
            flags = true
            uid = true
        }

        mails.forEach { mail ->
            println("${mail.uid.await()}: ${mail.subject.await()}")
            println("  from:  ${mail.from.await().joinToString()}")
            println("  flags: ${mail.flags.await().joinToString { it.value }}")
        }
    }
}
```

Message fields are `Deferred` — only what you request in `getMails { }` is fetched, so `await()`
returns immediately for those fields.

### Reading the message body

`getContent` writes the raw message, the plain text part and the HTML part into the streams you
pass in. All three streams are written concurrently, so all of them have to be consumed.

```kotlin
mail.content.getContent(
    rawStream = File("mail.eml").outputStream(),
    textStream = File("mail.txt").outputStream(),
    htmlStream = File("mail.html").outputStream(),
)
```

### Waiting for new mail (IDLE)

```kotlin
val idleFolder = inbox.getIdleFolder()
launch {
    idleFolder.idle {
        onNewMessage { uid -> println("new message: $uid") }
        onRemovedMessage { uid -> println("removed: $uid") }
        onFlagChanged { uid, flags -> println("flags of $uid: $flags") }
    }
}
// later
idleFolder.cancel()
```

## Name

Kamel is German for camel.
The original name was K-Mail (for Kotlin Mail), but
to avoid confusion with KMail (the KDE mail client) or
K9-Mail (the Android mail client), I renamed it to the
similar-sounding Kamel.

## Status

Early stage. The API is not stable yet, and there are no full docs — for anything not covered here,
look at the sources under `src/main/kotlin`.

## License

GPL-3.0, see [LICENSE](LICENSE).
