package es.jvbabi.overmail.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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

private data class Bodies(val text: String, val html: String)

/**
 * Routes [message] and returns what ended up in either stream.
 */
private fun bodiesOf(message: MimeMessage): Bodies {
    val text = ByteArrayOutputStream()
    val html = ByteArrayOutputStream()
    EmailBody.write(message, text, html)
    return Bodies(text.toString(Charsets.UTF_8), html.toString(Charsets.UTF_8))
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

        test("skips a body that is not text") {
            val bodies = bodiesOf(
                message("Content-Type: application/json; charset=utf-8", body = """{"a":1}""")
            )

            bodies.text shouldBe ""
            bodies.html shouldBe ""
        }

        test("skips a body disposed as an attachment") {
            val bodies = bodiesOf(
                message(
                    "Content-Type: text/html; charset=utf-8",
                    "Content-Disposition: attachment; filename=invoice.html",
                    body = "<p>invoice</p>"
                )
            )

            bodies.text shouldBe ""
            bodies.html shouldBe ""
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
})
