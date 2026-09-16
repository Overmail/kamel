package es.jvbabi.overmail.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.Properties

/**
 * Builds a message from [headers] (without the empty line) and a raw [body].
 */
private fun message(vararg headers: String, body: ByteArray): MimeMessage {
    val head = headers.joinToString("\r\n", postfix = "\r\n\r\n").toByteArray(Charsets.US_ASCII)
    return MimeMessage(Session.getInstance(Properties()), ByteArrayInputStream(head + body))
}

private fun message(vararg headers: String, body: String) =
    message(*headers, body = body.toByteArray(Charsets.UTF_8))

/**
 * Loads a message from `src/test/resources`.
 */
private fun messageResource(name: String): MimeMessage {
    val stream = checkNotNull(object {}.javaClass.getResourceAsStream("/$name")) { "missing resource $name" }
    return stream.use { MimeMessage(Session.getInstance(Properties()), it) }
}

internal fun sha256(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).toHexString()

private data class Bodies(val text: String, val html: String, val attachments: List<Email.Attachment>)

/**
 * Parses [message] with attachments and returns both bodies, a missing one as an empty string.
 */
private fun bodiesOf(message: MimeMessage): Bodies {
    val parts = EmailBody.parse(message, includeAttachments = true)
    return Bodies(parts.text.orEmpty(), parts.html.orEmpty(), parts.attachments)
}

class EmailBodyTest : FunSpec({

    context("single part messages") {
        test("writes a text/html body to the html stream") {
            // Regression: the html body used to end up in the text stream because the top level
            // body was routed without looking at the content type.
            val bodies = bodiesOf(
                message("Content-Type: text/html; charset=utf-8", body = "<!DOCTYPE html><p>Grüße</p>")
            )

            bodies.html shouldBe "<!DOCTYPE html><p>Grüße</p>"
            bodies.text shouldBe ""
        }

        test("writes a text/plain body to the text stream") {
            val bodies = bodiesOf(
                message("Content-Type: text/plain; charset=utf-8", body = "Grüße")
            )

            bodies.text shouldBe "Grüße"
            bodies.html shouldBe ""
        }

        test("treats a missing content type as text/plain") {
            // ASCII: without a Content-Type there is no charset either, so anything else would
            // assert jakarta.mail's default charset instead of the routing.
            val bodies = bodiesOf(message("Subject: no content type", body = "Gruesse"))

            bodies.text shouldBe "Gruesse"
            bodies.html shouldBe ""
        }

        test("treats an unparsable content type as text/plain") {
            val bodies = bodiesOf(message("Content-Type: ???", body = "Gruesse"))

            bodies.text shouldBe "Gruesse"
            bodies.html shouldBe ""
        }

        test("writes other text subtypes to the text stream") {
            val bodies = bodiesOf(
                message("Content-Type: text/calendar; charset=utf-8", body = "BEGIN:VCALENDAR")
            )

            bodies.text shouldBe "BEGIN:VCALENDAR"
            bodies.html shouldBe ""
        }

        test("treats a body that is not text as an attachment") {
            val bodies = bodiesOf(
                message("Content-Type: application/json; charset=utf-8", body = """{"a":1}""")
            )

            bodies.text shouldBe ""
            bodies.html shouldBe ""
            bodies.attachments.single().contentType shouldBe "application/json"
            bodies.attachments.single().data.toString(Charsets.UTF_8) shouldBe """{"a":1}"""
        }

        test("treats a body disposed as an attachment as an attachment") {
            val bodies = bodiesOf(
                message(
                    "Content-Type: text/html; charset=utf-8",
                    "Content-Disposition: attachment; filename=invoice.html",
                    body = "<p>invoice</p>"
                )
            )

            bodies.text shouldBe ""
            bodies.html shouldBe ""
            bodies.attachments.single().fileName shouldBe "invoice.html"
        }

        test("reports a message without a body as null") {
            val parts = EmailBody.parse(
                message("Content-Type: text/plain; charset=utf-8", body = "Grüße"),
                includeAttachments = false
            )

            parts.text shouldBe "Grüße"
            parts.html shouldBe null
        }
    }

    context("transfer encodings") {
        test("decodes quoted-printable html") {
            val bodies = bodiesOf(
                message(
                    "Content-Type: text/html; charset=utf-8",
                    "Content-Transfer-Encoding: quoted-printable",
                    body = "<p>Gr=C3=BC=C3=9Fe</p>"
                )
            )

            bodies.html shouldBe "<p>Grüße</p>"
            bodies.text shouldBe ""
        }

        test("reads html whose transfer encoding is a charset") {
            // Regression for #16: jakarta.mail throws "Unknown encoding: utf-8" for this part.
            val bodies = bodiesOf(
                message(
                    "Content-Type: text/html; charset=utf-8",
                    "Content-Transfer-Encoding: utf-8",
                    body = "<p>Grüße</p>"
                )
            )

            bodies.html shouldBe "<p>Grüße</p>"
            bodies.text shouldBe ""
        }
    }

    context("real mails") {
        // single-part-html.eml is a real newsletter (the kind of mail this bug was found on),
        // stripped of links, addresses and its stylesheets, and re-encoded as quoted-printable.
        test("routes a single part text/html newsletter to the html stream") {
            val bodies = bodiesOf(messageResource("single-part-html.eml"))

            bodies.text shouldBe ""
            bodies.html shouldStartWith "\r\n<!DOCTYPE HTML>"
            bodies.html shouldContain "unser Engagement für die Qualität von Windows"
        }

        // multipart-attachments.eml was sent from a webmailer with a small, a medium and a large attachment.
        test("splits a mail with attachments into bodies and attachments") {
            val bodies = bodiesOf(messageResource("multipart-attachments.eml"))

            bodies.text shouldStartWith "Hello, this email has a few attachments."
            bodies.html shouldContain "this is a test email"
            bodies.attachments.map { it.fileName } shouldBe listOf("logo_blue.svg", "Purple.heic", "Vicinae.dmg")
            bodies.attachments.map { it.contentType } shouldBe listOf("image/svg+xml", "image/heic", "application/x-diskcopy")
            bodies.attachments.map { it.isInline } shouldBe listOf(false, false, false)
            bodies.attachments.map { sha256(it.data) } shouldBe listOf(
                "250d6f077df6eec1c0bb3f6e9753863d7182618d5ed200b6a4f3bafc01b01844",
                "4960c8e46d2d9302e4436895d3125dc9b703e9aee3904b051c8d23e7e0b7778b",
                "db4af37676a643bd1729f2b1f3c8d667909a6d5286c9cde8053d1eae05c5670a",
            )
        }
    }

    context("multipart messages") {
        test("routes both alternatives of a multipart/alternative") {
            val bodies = bodiesOf(
                message(
                    "Content-Type: multipart/alternative; boundary=\"outer\"",
                    body = """
                        --outer
                        Content-Type: text/plain; charset=utf-8

                        Grüße
                        --outer
                        Content-Type: text/html; charset=utf-8

                        <p>Grüße</p>
                        --outer--
                    """.trimIndent().replace("\n", "\r\n")
                )
            )

            bodies.text.trim() shouldBe "Grüße"
            bodies.html.trim() shouldBe "<p>Grüße</p>"
        }

        test("descends into a nested multipart") {
            val bodies = bodiesOf(
                message(
                    "Content-Type: multipart/mixed; boundary=\"outer\"",
                    body = """
                        --outer
                        Content-Type: multipart/alternative; boundary="inner"

                        --inner
                        Content-Type: text/plain; charset=utf-8

                        Grüße
                        --inner
                        Content-Type: text/html; charset=utf-8

                        <p>Grüße</p>
                        --inner--
                        --outer
                        Content-Type: text/plain; charset=utf-8
                        Content-Disposition: attachment; filename=notes.txt

                        secret
                        --outer--
                    """.trimIndent().replace("\n", "\r\n")
                )
            )

            bodies.text.trim() shouldBe "Grüße"
            bodies.html.trim() shouldBe "<p>Grüße</p>"
        }

        test("skips a part disposed as an attachment") {
            val bodies = bodiesOf(
                message(
                    "Content-Type: multipart/mixed; boundary=\"outer\"",
                    body = """
                        --outer
                        Content-Type: text/plain; charset=utf-8

                        Grüße
                        --outer
                        Content-Type: text/html; charset=utf-8
                        Content-Disposition: attachment; filename=invoice.html

                        <p>do not show me</p>
                        --outer--
                    """.trimIndent().replace("\n", "\r\n")
                )
            )

            bodies.text.trim() shouldBe "Grüße"
            bodies.html shouldBe ""
        }
    }

    context("attachments") {
        val mixed = message(
            "Content-Type: multipart/mixed; boundary=\"outer\"",
            body = """
                --outer
                Content-Type: multipart/related; boundary="inner"

                --inner
                Content-Type: text/html; charset=utf-8

                <img src="cid:logo@kamel">
                --inner
                Content-Type: image/png
                Content-Transfer-Encoding: base64
                Content-ID: <logo@kamel>
                Content-Disposition: inline

                iVBORw==
                --inner--
                --outer
                Content-Type: application/pdf; name="=?utf-8?Q?Rechnung_M=C3=A4rz.pdf?="
                Content-Transfer-Encoding: base64
                Content-Disposition: attachment

                JVBERi0=
                --outer--
            """.trimIndent().replace("\n", "\r\n")
        )

        test("collects inline and attached parts with decoded data") {
            val bodies = bodiesOf(mixed)

            bodies.html.trim() shouldBe "<img src=\"cid:logo@kamel\">"
            bodies.attachments.size shouldBe 2

            val logo = bodies.attachments[0]
            logo.contentType shouldBe "image/png"
            logo.contentId shouldBe "logo@kamel"
            logo.isInline shouldBe true
            logo.data.toList() shouldBe listOf(0x89, 0x50, 0x4E, 0x47).map { it.toByte() }

            val invoice = bodies.attachments[1]
            invoice.contentType shouldBe "application/pdf"
            invoice.fileName shouldBe "Rechnung März.pdf"
            invoice.isInline shouldBe false
            invoice.data.toString(Charsets.US_ASCII) shouldBe "%PDF-"
        }

        test("leaves attachments out unless requested") {
            val parts = EmailBody.parse(mixed, includeAttachments = false)

            parts.html?.trim() shouldBe "<img src=\"cid:logo@kamel\">"
            parts.attachments shouldBe emptyList()
        }
    }
})
