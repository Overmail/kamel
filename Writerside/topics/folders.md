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

`getMailIds()`
: The sequence numbers of all messages in the folder, in ascending order. An empty list means the folder is empty.

`getIdByUid(uid)`
: The sequence number of the message with this UID, or `null` if the folder has no such message.

`getMails { }`
: Fetches messages, see [](fetching-messages.md).

`getIdleFolder()`
: Returns an object to watch the folder with, see [](idle.md).

```kotlin
val count = inbox.getMailIds().size
val position = inbox.getIdByUid(15201)
```

### Sequence numbers and UIDs {id="ids"}

IMAP has two ways to address a message, and Kamel exposes both:

| | Sequence number (`id`) | UID |
|---|------------------------|-----|
| Type | `Int` / `Long` | `Long` |
| Meaning | Position in the folder, starting at 1 | Identifier assigned by the server |
| Stable | No. Deleting a message shifts all later ones | Yes, within one folder |

Store UIDs if you need to find a message again later.

## Connections

A folder opens its own connections, up to five, and selects itself on each of them. See [](connection-pooling.md).
Closing the client closes them. `folder.close()` closes only those of this folder.
