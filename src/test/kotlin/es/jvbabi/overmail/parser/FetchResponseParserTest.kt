package es.jvbabi.overmail.parser

import es.jvbabi.overmail.core.Email
import es.jvbabi.overmail.core.EmailUser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import kotlin.time.Instant

private const val JANE = """("Jane Doe" NIL "jane" "example.org")"""
private const val JOHN = """("John Roe" NIL "john" "example.com")"""

/** Builds an untagged FETCH response with an ENVELOPE around the given fields. */
private fun envelopeLine(
    date: String = "Mon, 5 May 2025 14:03:12 +0200",
    subject: String = "\"Test subject\"",
    from: String = "($JANE)",
    sender: String = "($JANE)",
    replyTo: String = "NIL",
    to: String = "($JOHN)",
    cc: String = "NIL",
    bcc: String = "NIL",
    inReplyTo: String = "NIL",
    messageId: String = "\"<abc123@example.org>\""
) = "* 1 FETCH (ENVELOPE (\"$date\" $subject $from $sender $replyTo $to $cc $bcc $inReplyTo $messageId))"

/** A parser that fails if a continuation line is requested. */
private fun newParser() = FetchResponseParser()

/** A parser that hands out [lines] one by one when a literal continuation is requested. */
private fun newParser(vararg lines: String): FetchResponseParser {
    val remaining = lines.toMutableList()
    return FetchResponseParser { remaining.removeFirst() }
}

class FetchResponseParserTest : FunSpec({

    context("FLAGS") {
        test("parses a single flag") {
            newParser().parse("""* 1 FETCH (FLAGS (\Seen))""").flags shouldBe setOf(Email.Flag.Seen)
        }

        test("parses multiple flags") {
            newParser().parse("""* 1 FETCH (FLAGS (\Seen \Answered \Flagged))""").flags shouldBe
                    setOf(Email.Flag.Seen, Email.Flag.Answered, Email.Flag.Flagged)
        }

        test("parses an empty flag list") {
            newParser().parse("* 1 FETCH (FLAGS ())").flags shouldBe emptySet()
        }

        test("keeps unknown flags") {
            newParser().parse("""* 1 FETCH (FLAGS (\Seen ${'$'}Forwarded))""").flags shouldBe
                    setOf(Email.Flag.Seen, Email.Flag.Other("\$Forwarded"))
        }

        test("flags are null if the response does not contain them") {
            newParser().parse("* 1 FETCH (UID 42)").flags shouldBe null
        }
    }

    context("UID") {
        test("parses the uid") {
            newParser().parse("* 1 FETCH (UID 42)").uid shouldBe 42L
        }

        test("parses a uid that is not the last item") {
            newParser().parse("""* 1 FETCH (UID 42 FLAGS (\Seen))""").uid shouldBe 42L
        }

        test("parses uids larger than Int.MAX_VALUE") {
            newParser().parse("* 1 FETCH (UID 4294967295)").uid shouldBe 4294967295L
        }

        test("uid is null if the response does not contain it") {
            newParser().parse("""* 1 FETCH (FLAGS (\Seen))""").uid shouldBe null
        }

        test("fails on a non numeric uid") {
            shouldThrow<IllegalArgumentException> { newParser().parse("* 1 FETCH (UID abc)") }
        }
    }

    context("FLAGS and UID combined") {
        test("parses both, in any order") {
            val flagsFirst = newParser().parse("""* 1 FETCH (FLAGS (\Seen) UID 42)""")
            val uidFirst = newParser().parse("""* 1 FETCH (UID 42 FLAGS (\Seen))""")

            flagsFirst shouldBe ParsedFetchItem(flags = setOf(Email.Flag.Seen), uid = 42L)
            uidFirst shouldBe flagsFirst
        }

        test("no envelope means no envelope data") {
            newParser().parse("""* 1 FETCH (FLAGS (\Seen) UID 42)""").envelope shouldBe null
        }
    }

    context("ENVELOPE - date") {
        test("parses the date with a positive offset") {
            newParser().parse(envelopeLine()).envelope!!.sentAt shouldBe Instant.parse("2025-05-05T12:03:12Z")
        }

        test("parses the date with a negative offset") {
            newParser().parse(envelopeLine(date = "Mon, 5 May 2025 14:03:12 -0430"))
                .envelope!!.sentAt shouldBe Instant.parse("2025-05-05T18:33:12Z")
        }

        test("parses UT as UTC") {
            newParser().parse(envelopeLine(date = "5 May 2025 14:03:12 UT"))
                .envelope!!.sentAt shouldBe Instant.parse("2025-05-05T14:03:12Z")
        }

        test("parses GMT as UTC") {
            newParser().parse(envelopeLine(date = "5 May 2025 14:03:12 GMT"))
                .envelope!!.sentAt shouldBe Instant.parse("2025-05-05T14:03:12Z")
        }

        test("parses a date without the day of week") {
            newParser().parse(envelopeLine(date = "31 Dec 2024 23:59:59 +0000"))
                .envelope!!.sentAt shouldBe Instant.parse("2024-12-31T23:59:59Z")
        }

        test("parses a day of week without comma") {
            newParser().parse(envelopeLine(date = "Mon 5 May 2025 14:03:12 +0200"))
                .envelope!!.sentAt shouldBe Instant.parse("2025-05-05T12:03:12Z")
        }

        test("parses a two digit day of month") {
            newParser().parse(envelopeLine(date = "Tue, 15 Jul 2025 08:00:00 +0100"))
                .envelope!!.sentAt shouldBe Instant.parse("2025-07-15T07:00:00Z")
        }

        test("fails on a date it does not understand") {
            shouldThrow<IllegalArgumentException> { newParser().parse(envelopeLine(date = "2025-05-05T14:03:12Z")) }
        }
    }

    context("ENVELOPE - subject") {
        test("parses a quoted subject") {
            newParser().parse(envelopeLine()).envelope!!.subject shouldBe "Test subject"
        }

        test("parses NIL as no subject") {
            newParser().parse(envelopeLine(subject = "NIL")).envelope!!.subject shouldBe null
        }

        test("parses an empty subject") {
            newParser().parse(envelopeLine(subject = "\"\"")).envelope!!.subject shouldBe ""
        }

        test("unescapes quotes and backslashes") {
            // on the wire: "He said \"hi\" C:\\temp"
            val wireSubject = "\"He said \\\"hi\\\" C:\\\\temp\""

            newParser().parse(envelopeLine(subject = wireSubject))
                .envelope!!.subject shouldBe """He said "hi" C:\temp"""
        }

        test("mime decodes the subject") {
            newParser().parse(envelopeLine(subject = "\"=?UTF-8?B?R3LDvMOfZQ==?=\""))
                .envelope!!.subject shouldBe "Grüße"
        }

        test("a subject containing a date does not confuse the date parsing") {
            val parsed = newParser().parse(envelopeLine(subject = "\"Re: Mon, 1 Jan 2001 00:00:00 +0000\"")).envelope!!

            parsed.sentAt shouldBe Instant.parse("2025-05-05T12:03:12Z")
            parsed.subject shouldBe "Re: Mon, 1 Jan 2001 00:00:00 +0000"
        }

        test("reads a subject sent as literal from the next line") {
            val line = "* 1 FETCH (UID 7 ENVELOPE (\"Mon, 5 May 2025 14:03:12 +0200\" {12}"
            val continuation = "Hallo Welt!! ($JANE) NIL NIL NIL NIL NIL NIL \"<x@example.org>\")"

            val parsed = newParser(continuation).parse(line)
            val envelope = parsed.envelope.shouldNotBeNull()

            parsed.uid shouldBe 7L
            envelope.subject shouldBe "Hallo Welt!!"
            envelope.from shouldBe setOf(EmailUser("jane@example.org", "Jane Doe"))
        }
    }

    context("ENVELOPE - addresses") {
        test("parses all address fields") {
            val envelope = newParser().parse(
                envelopeLine(
                    from = "($JANE)",
                    sender = "($JANE)",
                    replyTo = "($JOHN)",
                    to = "($JOHN)",
                    cc = "($JANE $JOHN)",
                    bcc = "($JOHN)"
                )
            ).envelope.shouldNotBeNull()

            val jane = EmailUser("jane@example.org", "Jane Doe")
            val john = EmailUser("john@example.com", "John Roe")

            envelope.from shouldBe setOf(jane)
            envelope.senders shouldBe setOf(jane)
            envelope.replyTo shouldBe setOf(john)
            envelope.to shouldBe setOf(john)
            envelope.cc shouldBe setOf(jane, john)
            envelope.bcc shouldBe setOf(john)
        }

        test("parses NIL address lists as empty sets") {
            val envelope = newParser().parse(
                envelopeLine(replyTo = "NIL", cc = "NIL", bcc = "NIL")
            ).envelope.shouldNotBeNull()

            envelope.replyTo shouldBe emptySet()
            envelope.cc shouldBe emptySet()
            envelope.bcc shouldBe emptySet()
        }

        test("parses an address without a display name") {
            newParser().parse(envelopeLine(from = """((NIL NIL "noreply" "example.org"))"""))
                .envelope!!.from shouldBe setOf(EmailUser("noreply@example.org", null))
        }

        test("mime decodes display names") {
            newParser().parse(envelopeLine(from = """(("=?UTF-8?B?R3LDvMOfZQ==?=" NIL "gruesse" "example.org"))"""))
                .envelope!!.from shouldBe setOf(EmailUser("gruesse@example.org", "Grüße"))
        }

        test("deduplicates identical addresses") {
            newParser().parse(envelopeLine(to = "($JOHN $JOHN)")).envelope!!.to shouldBe
                    setOf(EmailUser("john@example.com", "John Roe"))
        }
    }

    context("ENVELOPE - in-reply-to and message-id") {
        test("parses in-reply-to and message-id") {
            val envelope = newParser().parse(
                envelopeLine(inReplyTo = "\"<parent@example.org>\"", messageId = "\"<abc123@example.org>\"")
            ).envelope.shouldNotBeNull()

            envelope.inReplyTo shouldBe "<parent@example.org>"
            envelope.messageId shouldBe "abc123@example.org"
        }

        test("parses NIL as no in-reply-to") {
            newParser().parse(envelopeLine(inReplyTo = "NIL")).envelope!!.inReplyTo shouldBe null
        }

        test("generates a message id if the server sends none") {
            val messageId = newParser().parse(envelopeLine(messageId = "NIL")).envelope!!.messageId

            messageId shouldStartWith "overmail-generated-id:"
            messageId.removePrefix("overmail-generated-id:").length shouldBe 40
        }

        test("the generated message id is stable for the same mail") {
            val first = newParser().parse(envelopeLine(messageId = "NIL")).envelope!!.messageId
            val second = newParser().parse(envelopeLine(messageId = "NIL")).envelope!!.messageId

            first shouldBe second
        }

        test("the generated message id differs for a different subject") {
            val first = newParser().parse(envelopeLine(subject = "\"a\"", messageId = "NIL")).envelope!!.messageId
            val second = newParser().parse(envelopeLine(subject = "\"b\"", messageId = "NIL")).envelope!!.messageId

            (first == second) shouldBe false
        }

        test("the generated message id differs for a different sender") {
            val first = newParser().parse(envelopeLine(from = "($JANE)", messageId = "NIL")).envelope!!.messageId
            val second = newParser().parse(envelopeLine(from = "($JOHN)", messageId = "NIL")).envelope!!.messageId

            (first == second) shouldBe false
        }
    }

    context("complete responses") {
        test("parses flags, uid and envelope in one response") {
            val line = """* 1 FETCH (FLAGS (\Seen) UID 42 ENVELOPE ("Mon, 5 May 2025 14:03:12 +0200" "Test subject" ($JANE) ($JANE) NIL ($JOHN) NIL NIL NIL "<abc123@example.org>"))"""

            val parsed = newParser().parse(line)

            parsed.flags shouldBe setOf(Email.Flag.Seen)
            parsed.uid shouldBe 42L
            parsed.envelope.shouldNotBeNull().let { envelope ->
                envelope.sentAt shouldBe Instant.parse("2025-05-05T12:03:12Z")
                envelope.subject shouldBe "Test subject"
                envelope.from shouldBe setOf(EmailUser("jane@example.org", "Jane Doe"))
                envelope.to shouldBe setOf(EmailUser("john@example.com", "John Roe"))
                envelope.messageId shouldBe "abc123@example.org"
            }
        }
    }

    context("error handling") {
        test("fails on an unknown fetch item") {
            shouldThrow<IllegalArgumentException> { newParser().parse("* 1 FETCH (RFC822.SIZE 1234)") }
        }

        test("keeps what was parsed before the failure") {
            val parser = newParser()

            shouldThrow<IllegalArgumentException> { parser.parse("""* 1 FETCH (FLAGS (\Seen) RFC822.SIZE 1234)""") }

            parser.partialResult() shouldBe ParsedFetchItem(flags = setOf(Email.Flag.Seen))
        }

        test("reports the position the parsing stopped at") {
            val line = """* 1 FETCH (FLAGS (\Seen) RFC822.SIZE 1234)"""
            val parser = newParser()

            shouldThrow<IllegalArgumentException> { parser.parse(line) }

            parser.line shouldBe line
            parser.remaining shouldBe "RFC822.SIZE 1234"
        }
    }
})
