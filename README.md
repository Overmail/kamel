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
import es.jvbabi.overmail.kamel.ImapClient
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    ImapClient(
        host = "imap.example.com",
        port = 993,
        ssl = true,
        auth = ImapClient.Auth.BasicAuth(
            username = System.getenv("IMAP_USERNAME"),
            password = System.getenv("IMAP_PASSWORD"),
        ),
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

### Logging in with OAuth 2.0

For providers that require OAuth (Gmail, Outlook/Microsoft 365), pass an access token instead of a
password. The client logs in with SASL `XOAUTH2`:

```kotlin
ImapClient(
    host = "imap.gmail.com",
    port = 993,
    auth = ImapClient.Auth.BearerAuth(
        username = "someone@gmail.com",
        bearer = accessToken, // the bare token, without "Bearer "
    ),
)
```

Obtaining and refreshing the token is up to you. The token is used whenever the client opens a new
connection, so build a new client once it has expired. A rejected token makes the call that opened
the connection throw an `ImapCommandException`.

### Reading the message body

`getContent` returns the raw message, the plain text body and the HTML body. Attachments are
only decoded if you ask for them.

```kotlin
val body = mail.getContent(includeAttachments = true)
File("mail.eml").writeBytes(body.raw)
body.text?.let { File("mail.txt").writeText(it) }
body.html?.let { File("mail.html").writeText(it) }
body.attachments.forEach { attachment ->
    // attachment.contentId is referenced by cid: urls in the HTML body
    File(attachment.fileName ?: "attachment").writeBytes(attachment.data)
}
```

To stream the message source instead, collect `getRawContent()`.

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

## Documentation

📖 **[Documentation](https://overmail.github.io/kamel/)**: tutorials and guides for every feature, plus the known limitations.

🔎 **[Kotlin API reference](https://overmail.github.io/kamel/api/)**: generated with Dokka.

Both are deployed with every release. To work on the docs locally:

- **Guides**: open [`Writerside/`](./Writerside) with the Writerside plugin of IntelliJ IDEA for a live preview
- **API docs**: `./gradlew dokkaGenerateHtml` → `build/dokka/html/index.html`

## Status

Early stage. The API is not stable yet.

## License

GPL-3.0, see [LICENSE](LICENSE).
