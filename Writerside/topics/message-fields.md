# Message fields

`getMails { }` returns `Email` objects. An `Email` gives access to the envelope, the flags and the UID of a
message, and to its [content](message-content.md).

## Reading fields

Every field is a `Deferred`. For fields that were part of the fetch, `await()` returns immediately:

```kotlin
val mail = inbox.getMails { getUid(15201); all() }.first()

val subject: String? = mail.subject.await()
val sentAt: Instant = mail.sentAt.await()
val from: Set<EmailUser> = mail.from.await()
```

| Field | Type | Requested with | Description |
|-------|------|----------------|-------------|
| `subject` | `String?` | `envelope` | Decoded subject, `null` if the message has none |
| `sentAt` | `kotlin.time.Instant` | `envelope` | The `Date` header |
| `from` | `Set<EmailUser>` | `envelope` | Authors |
| `senders` | `Set<EmailUser>` | `envelope` | The `Sender` header |
| `replyTo` | `Set<EmailUser>` | `envelope` | Where replies should go |
| `to` | `Set<EmailUser>` | `envelope` | Recipients |
| `cc` | `Set<EmailUser>` | `envelope` | Copy recipients |
| `bcc` | `Set<EmailUser>` | `envelope` | Blind copy recipients |
| `messageId` | `String` | `envelope` | The `Message-ID` header |
| `inReplyTo` | `String?` | `envelope` | `Message-ID` of the message this one answers |
| `uid` | `Long` | `uid` | UID within the folder |
| `flags` | `Set<Email.Flag>` | `flags` | See [](#flags) |

> Only await fields you requested. Loading a missing field afterwards is not implemented yet: `await()` throws a
> `NotImplementedError`. The failure happens in the coroutine scope of the client and cancels it, unless you
> passed a scope with a `SupervisorJob`.
{style="warning"}

### Without suspending {id="values"}

Each field has a companion property ending in `Value`, for example `subjectValue` or `flagsValue`. It is an
[`Optional`](utilities.md#optional) that tells you whether the field was fetched, without suspending and without
throwing:

```kotlin
val uid: Long? = mail.uidValue.getOrNull()

when (val flags = mail.flagsValue) {
    is Optional.Set -> println("Flags: ${flags.value}")
    is Optional.Empty -> println("Flags were not requested")
}
```

## Addresses {id="addresses"}

`EmailUser` is one entry of an address field:

| Property | Type | Description |
|----------|------|-------------|
| `address` | `String` | The mail address, for example `ada@example.com` |
| `name` | `String?` | The display name, decoded and without surrounding quotes. `null` if there is none |

`toString()` formats it the usual way:

```kotlin
println(EmailUser("ada@example.com", "Ada Lovelace"))  // Ada Lovelace <ada@example.com>
println(EmailUser("ada@example.com", null))            // ada@example.com
```

Two `EmailUser` objects are equal if address and name are equal.

## Flags {id="flags"}

`Email.Flag` covers the system flags of IMAP and any keyword a server or client defines:

| Flag | `value` | Meaning |
|------|---------|---------|
| `Email.Flag.Seen` | `\Seen` | The message was read |
| `Email.Flag.Answered` | `\Answered` | The message was replied to |
| `Email.Flag.Flagged` | `\Flagged` | Marked as important |
| `Email.Flag.Deleted` | `\Deleted` | Marked for removal |
| `Email.Flag.Draft` | `\Draft` | The message is a draft |
| `Email.Flag.Recent` | `\Recent` | New since the folder was last opened |
| `Email.Flag.Other(value)` | Any other | Keywords such as `$Forwarded` or `NonJunk` |

```kotlin
val flags = mail.flags.await()

val unread = Email.Flag.Seen !in flags
val keywords = flags.filterIsInstance<Email.Flag.Other>().map { it.value }
```

`Email.Flag.fromString` turns the IMAP spelling into a flag:

```kotlin
Email.Flag.fromString("\\Seen")      // Email.Flag.Seen
Email.Flag.fromString("\$Forwarded") // Email.Flag.Other("$Forwarded")
```

Flags are read-only at the moment, see [](known-limitations.md).

## Printing a message {id="printing"}

For a quick look while developing, `print()` writes all fields of a message to standard output. It awaits every
field, so the fetch must have requested all of them:

```kotlin
inbox.getMails { getUid(15201); all() }.forEach { it.print() }
```

```text
15201: Your invoice for October
    From: Billing <billing@example.com>
    Sender: Billing <billing@example.com>
    To: someone@example.com
    Cc: 
    Bcc: 
    Date: 2026-10-09T08:12:44Z
    Flags: \Seen
    In-Reply-To: <none>
    Message-ID: <20261009081244.1234@example.com>
```

`toString()` returns a similar block and never suspends. Fields that were not fetched show up as `Empty()`.
