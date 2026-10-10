# Known limitations

Kamel is young. This page lists what it does not do yet, so you can decide what to build around it. If one of
these blocks you, [open an issue](https://github.com/Overmail/kamel/issues).

## Code quality before 1.0 {id="pre-1-0"}

Much of the code in the releases before 1.0 was generated with AI tools. It works for the cases it was built and
tested for, but it has not yet had the review a library you rely on deserves, so expect rough edges and bugs.

On the way to 1.0, the library is being optimized and reimplemented piece by piece to make the code more
reliable. Until then, pin the version you use and test upgrades against your mail servers.

## Scope

- **Reading only.** There is no way to change flags, move, copy, delete or append messages, or to create, rename
  and delete folders.
- **IMAP only.** Sending mail (SMTP) and POP3 are not part of the library.
- **No search.** Messages are selected by sequence number, range or UID. Searching by sender, date or text on the
  server is not available.

## Connecting

- **No `STARTTLS`.** `ssl = true` uses TLS from the first byte (port 993). `ssl = false` is unencrypted.
- **Passwords are not escaped.** With `BasicAuth`, a username or password containing `"` or `\` makes the login
  fail.
- **No token refresh.** A client keeps the access token it was created with, see [](tutorial-oauth.md).
- **No connect timeout.** [`readTimeout`](connecting.md) covers a server that stops answering. Opening the
  connection itself is not limited: wrap the first call in `withTimeout` if that matters.

## Folders

- **Folder names are not decoded.** IMAP encodes non-ASCII folder names in modified UTF-7. They are returned as
  the server sends them, for example `Entw&APw-rfe` for `Entwürfe`. Use [`specialType`](folders.md#special) to find
  the common folders.
- **`getFolders(onlyRoot = true)`** returns the hierarchy root as servers report it, not the top-level folders.

## Fetching

- **Fields are not loaded later.** Awaiting a field that was not fetched throws an `IllegalStateException`, see
  [](message-fields.md).
- **A fetch by sequence number selects first.** It asks for the size of the folder before it fetches, see
  [](fetching-messages.md#selection). A fetch by UID does not.
- **A fetch that is not collected to its end can cost a connection.** If the rest of the response is not
  already there to be skipped, the connection is closed and replaced by a new one.

## Content

- **Bodies are loaded as a whole.** `getContent()` holds the complete message in memory. There is no way to fetch
  a single part or attachment. Use [`getRawContent()`](message-content.md#raw) to stream the source.

## IDLE

- **Sequence numbers, not UIDs.** See [](idle.md#events).
- **No automatic reconnect or renewal.** See [](idle.md#long-running).
- **No read timeout while idling.** A connection that died silently is only noticed when the `IDLE` is renewed.
- **Callbacks of `idle { }` cannot suspend.** Collect [`events()`](idle.md#events) instead.

## Platform

- **JVM 26 only.** The library is not multiplatform and is compiled for Java 26.
- **The API is not stable.** Names and signatures can change between minor versions until 1.0.
