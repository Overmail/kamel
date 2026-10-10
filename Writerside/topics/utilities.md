# Utilities

The package `es.jvbabi.overmail.util` contains helpers Kamel uses itself. They are public, so you can use them
when you work with mail data that did not come through Kamel.

## MimeUtility {id="mime-utility"}

Mail headers can only carry ASCII. Everything else is written as an "encoded word", for example
`=?UTF-8?B?R3LDvMOfZQ==?=`. Kamel decodes subjects, display names and attachment names for you. `MimeUtility` does
the same for a header you got elsewhere.

### decode

```kotlin
MimeUtility.decode("=?UTF-8?B?R3LDvMOfZQ==?= aus =?ISO-8859-1?Q?M=FCnchen?=")
// Grüße aus München
```

- Supports Base64 (`B`) and Q encoding (`Q`), in any charset the JVM knows.
- Unfolds header values that span several lines, so the result never contains a line break.
- Drops the whitespace between two adjacent encoded words, as the standard demands.
- Leaves encoded words with an unknown charset or broken content untouched.

### decodeQuotedPrintable

Decodes the Q encoding of a single encoded word and reads the result as UTF-8. `=XX` becomes the byte with that
hex value, `_` becomes a space:

```kotlin
MimeUtility.decodeQuotedPrintable("Gr=C3=BC=C3=9Fe_aus_M=C3=BCnchen")
// Grüße aus München
```

## Optional {id="optional"}

`Optional<T>` distinguishes "not loaded" from "loaded, and the value is `null`". The `...Value` properties of
[`Email`](message-fields.md#values) use it.

`Optional.Empty()`
: Nothing was loaded.

`Optional.Set(value)`
: A value was loaded. `value` can be `null` if `T` is nullable.

`getOrNull()`
: The value, or `null` for `Empty`.

```kotlin
when (val subject = mail.subjectValue) {
    is Optional.Empty -> println("Subject was not fetched")
    is Optional.Set -> println("Subject: ${subject.value ?: "<none>"}")
}
```

## mapAsync {id="map-async"}

`mapAsync` maps a collection with a `suspend` function, running all elements concurrently, and waits for all
results. The order of the results matches the order of the input.

It takes the `CoroutineScope` to launch in as a context parameter:

```kotlin
context(scope: CoroutineScope)
suspend fun subjectsOf(mails: List<Email>): Collection<String?> =
    mails.mapAsync { it.subject.await() }
```

Combined with [](connection-pooling.md), this is a short way to download several bodies at once:

```kotlin
context(scope: CoroutineScope)
suspend fun download(mails: List<Email>): Collection<Email.Content> =
    mails.mapAsync { it.getContent() }
```
