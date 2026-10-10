# Message content

The body of a message is downloaded separately from its [fields](message-fields.md), and only when you ask for it.

`getContent()` and `getRawContent()` need the UID of the message, so fetch it with `uid = true`:

```kotlin
val mail = inbox.getMails {
    getUid(15201)
    envelope = true
    uid = true
}.first()
```

Downloading a body does not set the `\Seen` flag. The message stays unread on the server.

## Text, HTML and attachments {id="content"}

`getContent()` downloads the message and splits it into its parts:

```kotlin
val content: Email.Content = mail.getContent(includeAttachments = true)

println(content.text)
println(content.html)
println(content.attachments.size)
```

`includeAttachments` (default: `false`)
: Attachments are only decoded and returned if this is `true`. The message is downloaded completely in both cases.

`Email.Content` has these properties:

| Property | Type | Description |
|----------|------|-------------|
| `raw` | `ByteArray` | The message source, byte for byte. Save it as `.eml` |
| `text` | `String?` | All plain text parts joined, `null` if the message has none |
| `html` | `String?` | All HTML parts joined, `null` if the message has none |
| `attachments` | `List<Email.Attachment>` | Empty unless requested |

Transfer encodings (Base64, quoted-printable) and charsets are already decoded.

### What ends up where

- `text/html` parts go to `html`.
- Every other `text/*` part goes to `text`. That includes `text/calendar` or `text/csv`, which are sometimes the
  only body a message has.
- Parts marked as attachment, and every part that is not text, go to `attachments`.

A message written in a mail client usually has both `text` and `html`, with the same content in two forms.

## Attachments {id="attachments"}

| Property | Type | Description |
|----------|------|-------------|
| `fileName` | `String?` | Decoded file name, `null` if the part names none |
| `contentType` | `String` | Lowercase base type, for example `application/pdf`. `application/octet-stream` if unknown |
| `contentId` | `String?` | `Content-ID` without angle brackets |
| `isInline` | `Boolean` | `true` for parts with `Content-Disposition: inline` |
| `data` | `ByteArray` | The decoded content |

```kotlin
content.attachments.forEach { attachment ->
    println("${attachment.fileName} (${attachment.contentType}, ${attachment.data.size} bytes)")
}
```

> `fileName` is chosen by the sender. Strip directories from it before you use it in a path, as shown in
> [](tutorial-save-attachments.md).
{style="warning"}

### Inline images

Images embedded in an HTML body are attachments with `isInline == true`. The HTML refers to them with a `cid:` URL
that matches `contentId`:

```html
<img src="cid:logo@example.com">
```

```kotlin
val logo = content.attachments.first { it.contentId == "logo@example.com" }
```

## Raw source as a stream {id="raw"}

`getRawContent()` returns the message source as a cold `Flow<ByteArray>`. Each collection downloads the message
and emits it in chunks of up to 8 KiB, so the whole message never has to fit into memory:

```kotlin
File("mail.eml").outputStream().use { output ->
    mail.getRawContent().collect { chunk -> output.write(chunk) }
}
```

Use it for large messages, or when you want to parse the source with a library of your own.

## Creating an email from its source {id="parse"}

`Email.parse()` turns a message source back into an `Email`, for example a stored `.eml` file or the bytes
`getRawContent()` returned earlier. It needs no connection:

```kotlin
val mail = File("mail.eml").inputStream().use { Email.parse(it) }

println(mail.subject.await())
println(mail.getContent(includeAttachments = true).attachments.size)
```

It accepts three kinds of source:

| Source | Notes |
|--------|-------|
| `ByteArray` | The complete message source |
| `InputStream` | Read to its end. The read blocks, and the stream is not closed |
| `Flow<ByteArray>` | Collected first, so this overload suspends. Fits `getRawContent()` |

The envelope fields are read from the headers, the same way a server fills them: `senders` and `replyTo` fall
back to `from` if the message has no `Sender` or `Reply-To` header. `getContent()` and `getRawContent()` work on
the source you passed in.

> A message source has no UID and no flags. `uid.await()` and `flags.await()` throw an `IllegalStateException`
> for a parsed email, and so does `sentAt.await()` if the message has no readable `Date` header. Check
> `uidValue`, `flagsValue` and `sentAtValue` if you are not sure, see [](message-fields.md#values).
{style="warning"}

`Email.parse()` throws an `IllegalArgumentException` if the source cannot be read as a message.

## Errors

`getContent()` and `getRawContent()` throw an `ImapCommandException` if the server refuses the download, and an
`ImapConnectionClosedException` if the connection breaks in the middle. See [](error-handling.md).
