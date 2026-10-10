package es.jvbabi.overmail.kamel

import es.jvbabi.overmail.kamel.util.Optional
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlin.time.Instant

/**
 * Builds a message source from [headers] (without the empty line) and a [body].
 */
private fun source(vararg headers: String, body: String = "Hello"): ByteArray =
    (headers.joinToString("\r\n", postfix = "\r\n\r\n") + body).toByteArray(Charsets.UTF_8)

private val multipart = source(
    "From: Ada Lovelace <ada@example.com>",
    "To: charles@example.com",
    "Subject: Notes",
    "Date: Tue, 6 Oct 2026 16:54:06 +0200",
    "Message-ID: <notes-1@example.com>",
    "MIME-Version: 1.0",
    "Content-Type: multipart/mixed; boundary=outer",
    body = listOf(
        "--outer",
        "Content-Type: multipart/alternative; boundary=inner",
        "",
        "--inner",
        "Content-Type: text/plain; charset=utf-8",
        "Content-Transfer-Encoding: quoted-printable",
        "",
        "Gr=C3=BC=C3=9Fe",
        "--inner",
        "Content-Type: text/html; charset=utf-8",
        "",
        "<p>Grüße</p>",
        "--inner--",
        "--outer",
        "Content-Type: application/pdf; name=notes.pdf",
        "Content-Disposition: attachment; filename=notes.pdf",
        "Content-Transfer-Encoding: base64",
        "",
        "JVBERi0xLjQ=",
        "--outer--",
        "",
    ).joinToString("\r\n")
)

class EmailParseTest : FunSpec({

    context("envelope") {
        test("reads the fields from the headers") {
            val email = Email.parse(
                source(
                    "From: Ada Lovelace <ada@example.com>",
                    "Sender: Office <office@example.com>",
                    "Reply-To: replies@example.com",
                    "To: Charles Babbage <charles@example.com>, mary@example.com",
                    "Cc: \"Byron, Anne\" <anne@example.com>",
                    "Bcc: hidden@example.com",
                    "Subject: Notes on the engine",
                    "Date: Tue, 6 Oct 2026 16:54:06 +0200",
                    "Message-ID: <notes-1@example.com>",
                    "In-Reply-To: <sketch-7@example.com>",
                )
            )

            email.subject.await() shouldBe "Notes on the engine"
            email.sentAt.await() shouldBe Instant.parse("2026-10-06T14:54:06Z")
            email.from.await() shouldBe setOf(EmailUser("ada@example.com", "Ada Lovelace"))
            email.senders.await() shouldBe setOf(EmailUser("office@example.com", "Office"))
            email.replyTo.await() shouldBe setOf(EmailUser("replies@example.com", null))
            email.to.await() shouldBe setOf(
                EmailUser("charles@example.com", "Charles Babbage"),
                EmailUser("mary@example.com", null),
            )
            email.cc.await() shouldBe setOf(EmailUser("anne@example.com", "Byron, Anne"))
            email.bcc.await() shouldBe setOf(EmailUser("hidden@example.com", null))
            email.messageId.await() shouldBe "notes-1@example.com"
            // With angle brackets, like the ENVELOPE of a server.
            email.inReplyTo.await() shouldBe "<sketch-7@example.com>"
        }

        test("decodes encoded words and unfolds a folded subject") {
            val email = Email.parse(
                source(
                    "From: =?UTF-8?Q?J=C3=BCrgen_M=C3=BCller?= <juergen@example.com>",
                    "Subject: =?UTF-8?B?R3LDvMOfZQ==?=",
                    " =?UTF-8?Q?_aus_K=C3=B6ln?=",
                )
            )

            email.subject.await() shouldBe "Grüße aus Köln"
            email.from.await() shouldBe setOf(EmailUser("juergen@example.com", "Jürgen Müller"))
        }

        test("falls back to From for a missing Sender and Reply-To") {
            val email = Email.parse(source("From: ada@example.com"))

            email.senders.await() shouldBe setOf(EmailUser("ada@example.com", null))
            email.replyTo.await() shouldBe setOf(EmailUser("ada@example.com", null))
        }

        test("returns the members of a group") {
            val email = Email.parse(source("To: Team: ada@example.com, charles@example.com;"))

            email.to.await() shouldBe setOf(EmailUser("ada@example.com", null), EmailUser("charles@example.com", null))
        }

        test("leaves missing headers empty") {
            val email = Email.parse(source("X-Mailer: none"))

            email.subject.await() shouldBe null
            email.inReplyTo.await() shouldBe null
            email.from.await().shouldBeEmpty()
            email.to.await().shouldBeEmpty()
        }

        test("generates a stable message id if the message has none") {
            val headers = arrayOf("From: ada@example.com", "Subject: Notes", "Date: Tue, 6 Oct 2026 16:54:06 +0200")

            val messageId = Email.parse(source(*headers)).messageId.await()

            messageId shouldStartWith "overmail-generated-id:"
            Email.parse(source(*headers, body = "Another body")).messageId.await() shouldBe messageId
        }
    }

    context("fields a message source does not carry") {
        test("fails for uid and flags instead of loading them") {
            val email = Email.parse(source("From: ada@example.com"))

            email.uidValue.shouldBeInstanceOf<Optional.Empty<Long>>()
            email.flagsValue.shouldBeInstanceOf<Optional.Empty<Set<Email.Flag>>>()
            shouldThrow<IllegalStateException> { email.uid.await() }.message shouldContain "uid is not available"
            shouldThrow<IllegalStateException> { email.flags.await() }
        }

        test("fails for sentAt without a readable Date header") {
            shouldThrow<IllegalStateException> { Email.parse(source("From: ada@example.com")).sentAt.await() }
            shouldThrow<IllegalStateException> { Email.parse(source("Date: yesterday")).sentAt.await() }
        }
    }

    context("content") {
        test("splits the source into bodies and attachments") {
            val content = Email.parse(multipart).getContent(includeAttachments = true)

            content.raw shouldBe multipart
            content.text?.trim() shouldBe "Grüße"
            content.html?.trim() shouldBe "<p>Grüße</p>"
            content.attachments shouldHaveSize 1
            content.attachments.single().fileName shouldBe "notes.pdf"
            content.attachments.single().contentType shouldBe "application/pdf"
            content.attachments.single().data.toString(Charsets.US_ASCII) shouldBe "%PDF-1.4"
        }

        test("returns the source byte for byte") {
            val chunks = Email.parse(multipart).getRawContent().toList()

            chunks.fold(ByteArray(0)) { all, chunk -> all + chunk } shouldBe multipart
        }
    }

    context("sources") {
        test("reads an input stream") {
            Email.parse(multipart.inputStream()).subject.await() shouldBe "Notes"
        }

        test("collects a flow of chunks") {
            val chunks = flowOf(multipart.copyOfRange(0, 100), multipart.copyOfRange(100, multipart.size))

            val email = Email.parse(chunks)

            email.subject.await() shouldBe "Notes"
            email.getContent().raw shouldBe multipart
        }

        test("parses what another email returned as its raw content") {
            val original = Email.parse(multipart)

            val copy = Email.parse(original.getRawContent())

            copy.messageId.await() shouldBe original.messageId.await()
            copy.getContent().text shouldBe original.getContent().text
        }
    }
})
