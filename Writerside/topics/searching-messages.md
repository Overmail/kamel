# Searching messages

`search { }` asks the server for the messages that match some criteria and returns their UIDs. Nothing is fetched
for it, so finding one message no longer means fetching the envelopes of a whole folder.

```kotlin
val uids: List<Long> = inbox.search {
    subject("Lunch tomorrow")
    sentOn(LocalDate(2025, 5, 5))
}

inbox.getMails { uids(uids); all() }.collect { mail -> println(mail.subject.await()) }
```

The UIDs come back lowest first and go straight into [`uids(...)`](fetching-messages.md#selection). Without any
criterion, `search { }` finds every message of the folder. Dates are `kotlinx.datetime.LocalDate`.

## Criteria {id="criteria"}

`subject(text)`
: The `Subject` contains `text`.

`header(field, text)`
: The header `field` contains `text`. An empty `text` finds every message that has the header at all.

`messageId(id)`
: The `Message-ID` header contains `id`. Pass it with or without the angle brackets.

`sentOn(date)`, `sentSince(date)`, `sentBefore(date)`
: The day the `Date` header names is `date`, `date` or later, or before `date`.

`receivedOn(date)`, `receivedSince(date)`, `receivedBefore(date)`
: The same for the day the message arrived in the folder: the internal date the server keeps. For most messages
  that is the day of delivery, for a copied or uploaded one the day it was copied or uploaded.

Strings match where the field contains them, ignoring case. Dates are whole days: the time and the time zone are
not looked at. A search for a message sent at a certain second finds every message of that day, so compare the
fetched [`sentAt`](message-fields.md) afterwards.

`sentOn` reads the day in the time zone the `Date` header is written in, which can be a day ahead of or behind
UTC. If you only know the instant, search the days around it:

```kotlin
val sentAt: Instant = …
val day = sentAt.toLocalDateTime(TimeZone.UTC).date
val uids = inbox.search {
    subject(subject)
    sentSince(day.minus(1, DateTimeUnit.DAY))
    sentBefore(day.plus(2, DateTimeUnit.DAY))
}
val mail = inbox.getMails { uids(uids); envelope = true }.firstOrNull { it.sentAt.await() == sentAt }
```

## Combining criteria {id="combining"}

Criteria next to each other all have to match. `or { }` needs one of the criteria inside it, `not { }` negates
them, and `and { }` groups criteria inside an `or`:

```kotlin
inbox.search {
    sentSince(LocalDate(2025, 1, 1))
    or {
        messageId("<x@example.org>")
        and {
            subject("Invoice")
            header("From", "billing@example.com")
        }
    }
    not { header("X-Spam-Flag", "YES") }
}
```

`not { }` with several criteria negates them together: it finds the messages that do not match all of them. For
the messages that match none of them, put an `or` inside: `not { or { … } }`.

Criteria built elsewhere are added with `add(criterion)`. They are the classes of `SearchCriterion`.

## What goes over the wire {id="wire"}

A search is one `UID SEARCH` on a connection of the [folder pool](connection-pooling.md):

```
UID SEARCH SENTSINCE 1-Jan-2025 OR HEADER "Message-ID" "<x@example.org>" (SUBJECT "Invoice" HEADER "From" "billing@example.com") NOT HEADER "X-Spam-Flag" "YES"
```

Strings are sent as quoted strings. A string that is not ASCII is sent as a literal, and the command names
`CHARSET UTF-8`. A server that does not support that charset refuses the search with an `ImapCommandException`
whose response carries `BADCHARSET`.

## Testing {id="testing"}

The in-memory folder of [`kamel-test`](testing.md#search) searches its mails with the same criteria.
