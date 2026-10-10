# Folders

A folder (IMAP calls it a mailbox) holds messages. You get folders from the client.

## List folders

```kotlin
val folders: List<ImapFolder> = client.getFolders()
```

This returns every folder of the account, on all levels of the hierarchy.

`onlyRoot` (default: `false`)
: `true` sends `LIST "" ""` instead of `LIST "" "*"`. Servers answer that with the root of the hierarchy and its
delimiter, not with the top-level folders. To get those, filter the full list: `folders.filter { it.path.size == 1 }`.

## Folder properties

| Property | Type | Example | Description |
|----------|------|---------|-------------|
| `path` | `List<String>` | `[Archive, 2025]` | The segments of the folder name |
| `delimiter` | `String` | `/` | What the server puts between the segments |
| `fullName` | `String` | `Archive/2025` | The name as the server knows it |
| `name` | `String` | `2025` | The last segment |
| `specialType` | `SpecialType?` | `null` | What the folder is for, if the server says so |

## Special folders {id="special"}

Folder names differ between providers and languages. `specialType` identifies the common ones regardless of their
name:

| `ImapFolder.SpecialType` | Detected by |
|--------------------------|-------------|
| `INBOX` | The top-level folder named `INBOX` |
| `SENT` | The `\Sent` attribute |
| `DRAFTS` | The `\Drafts` attribute |
| `SPAM` | The `\Junk` attribute |
| `TRASH` | The `\Trash` attribute |

```kotlin
val inbox = folders.first { it.specialType == ImapFolder.SpecialType.INBOX }
val trash = folders.firstOrNull { it.specialType == ImapFolder.SpecialType.TRASH }
```

Servers that do not announce these attributes only get `INBOX` detected. Fall back to the name there.

## Messages in a folder

`state()`, `status()`
: What the server reports about the folder right now, see [](#state).

`getMailIds()`
: The sequence numbers of all messages in the folder, in ascending order. An empty list means the folder is empty.
This lists the whole folder; to find out what is new, use [`state()`](#state) and a
[UID range](fetching-messages.md#selection).

`getIdByUid(uid)`
: The sequence number of the message with this UID, or `null` if the folder has no such message.

`getMails { }`
: Fetches messages, see [](fetching-messages.md).

`getIdleFolder()`
: Returns an object to watch the folder with, see [](idle.md).

```kotlin
val count = inbox.state().exists
val position = inbox.getIdByUid(15201)
```

### Folder state {id="state"}

`state()` and `status()` both return what the server reports about the folder:

| Property | Type | Description |
|----------|------|-------------|
| `uidValidity` | `Long` | Changes when the server reassigned the UIDs of the folder |
| `uidNext` | `Long?` | The UID the next new message gets at least. `null` if the server does not say |
| `exists` | `Int` | The number of messages in the folder |

`state()`
: Asks with a `SELECT`, on a connection of the folder. Use it when you work with the folder anyway.

`status()`
: Asks with a `STATUS`, on a connection of the client. No connection of the folder is opened or selected for it,
which makes it the cheap way to check many folders for changes before deciding which ones to open.

```kotlin
val changed = client.getFolders().filter { it.status().uidNext != storedUidNext[it.fullName] }
```

UIDs are only stable while `uidValidity` stays the same. Store it next to the UIDs you keep, and throw those
away when it changes:

```kotlin
val state = inbox.state()
if (state.uidValidity != storedUidValidity) {
    // every stored UID is void: start over
}

inbox.getMails {
    uidRange(lastKnownUid + 1)
    all()
}.collect { mail ->
    // only what arrived since
}
```

### Sequence numbers and UIDs {id="ids"}

IMAP has two ways to address a message, and Kamel exposes both:

| | Sequence number (`id`) | UID |
|---|------------------------|-----|
| Type | `Int` / `Long` | `Long` |
| Meaning | Position in the folder, starting at 1 | Identifier assigned by the server |
| Stable | No. Deleting a message shifts all later ones | Yes, within one folder and one [`uidValidity`](#state) |

Store UIDs if you need to find a message again later.

## Without a server {id="interfaces"}

`ImapFolder` implements the interface `MailFolder`, and `ImapClient` implements `MailClient`. Code written against
the interfaces can be tested with folders that live in memory, see [](testing.md).

## Connections

A folder opens its own connections, up to five, and selects itself on each of them. See [](connection-pooling.md).
Closing the client closes them. `folder.close()` closes only those of this folder.
