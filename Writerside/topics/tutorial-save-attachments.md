# Tutorial: Save a message and its attachments

In this tutorial you download one message and write its source, its text, its HTML and its attachments to disk.

You will use `getUid()`, `getContent()` and `getRawContent()`.

## Before you start

- You have a working client and an `inbox` folder, as built in [](tutorial-inbox-viewer.md).
- You know the UID of a message with an attachment. The inbox viewer can print it with `mail.uid.await()`.

## Fetch the message

<procedure title="Get one message by UID" id="fetch-by-uid">
<step>

Select the message with `getUid`. Request `uid` as well: Kamel needs it to download the body.

```kotlin
val mail = inbox.getMails {
    getUid(15201)
    envelope = true
    uid = true
}.firstOrNull() ?: error("No message with this UID")
```

> A fetch without `envelope = true` returns no messages, see [](fetching-messages.md#fields).
{style="note"}

</step>
</procedure>

## Save the body

<procedure title="Write text, HTML and source" id="save-body">
<step>

Download the content. Attachments are only decoded when you ask for them:

```kotlin
val content = mail.getContent(includeAttachments = true)
```

</step>
<step>

Create a directory and write the parts that exist. A message can have a text body, an HTML body, both or neither:

```kotlin
val directory = File("mail-${mail.uid.await()}").apply { mkdirs() }

File(directory, "message.eml").writeBytes(content.raw)
content.text?.let { File(directory, "message.txt").writeText(it) }
content.html?.let { File(directory, "message.html").writeText(it) }
```

</step>
</procedure>

## Save the attachments

<procedure title="Write every attachment" id="save-attachments">
<step>

The file name comes from the sender, so do not use it as a path. Keep only its last segment and fall back to a
generated name:

```kotlin
content.attachments.forEachIndexed { index, attachment ->
    val safeName = attachment.fileName
        ?.let { File(it).name }
        ?.ifBlank { null }
        ?: "attachment-$index"

    File(directory, safeName).writeBytes(attachment.data)
    println("$safeName (${attachment.contentType}, ${attachment.data.size} bytes)")
}
```

```text
invoice.pdf (application/pdf, 48211 bytes)
logo.png (image/png, 3120 bytes)
```

</step>
<step>

Images embedded in the HTML body are attachments as well. They have `isInline == true` and a `contentId`, which
the HTML references as `cid:` URL. To show the saved HTML in a browser, point those URLs at the files:

```kotlin
var html = content.html.orEmpty()
content.attachments.forEach { attachment ->
    val contentId = attachment.contentId ?: return@forEach
    val fileName = attachment.fileName ?: return@forEach
    html = html.replace("cid:$contentId", File(fileName).name)
}

File(directory, "message.html").writeText(html)
```

</step>
</procedure>

## Large messages: stream the source

`getContent()` holds the whole message in memory. To save a large message without doing that, collect
`getRawContent()`. It emits the source in chunks:

```kotlin
File(directory, "message.eml").outputStream().use { output ->
    mail.getRawContent().collect { chunk -> output.write(chunk) }
}
```

## What you learned

- `getContent()` gives you decoded text, HTML and attachments. More in [](message-content.md).
- Downloading a body does not mark the message as read.
- Attachment names are untrusted input.

Next: [](tutorial-watch-mailbox.md).
