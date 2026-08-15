package es.jvbabi.overmail.util

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.inspectors.forAll
import io.kotest.matchers.shouldBe
import jakarta.mail.internet.ContentType
import jakarta.mail.internet.MimeBodyPart
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.io.encoding.Base64

/**
 * Builds a MIME part from [headers] (without the empty line) and a raw [body].
 */
private fun part(vararg headers: String, body: ByteArray): MimeBodyPart {
    val head = headers.joinToString("\r\n", postfix = "\r\n\r\n").toByteArray(Charsets.US_ASCII)
    return MimeBodyPart(ByteArrayInputStream(head + body))
}

private fun part(vararg headers: String, body: String) =
    part(*headers, body = body.toByteArray(Charsets.UTF_8))

class MimeContentTest : FunSpec({

    context("isSupportedEncoding") {
        test("accepts the encodings defined by RFC 2045") {
            listOf("7bit", "8bit", "binary", "base64", "quoted-printable").forAll {
                MimeContent.isSupportedEncoding(it) shouldBe true
            }
        }

        test("accepts uuencode") {
            MimeContent.isSupportedEncoding("x-uuencode") shouldBe true
        }

        test("ignores case and surrounding whitespace") {
            MimeContent.isSupportedEncoding(" Base64 ") shouldBe true
        }

        test("treats a missing or empty encoding as unencoded") {
            MimeContent.isSupportedEncoding(null) shouldBe true
            MimeContent.isSupportedEncoding("") shouldBe true
        }

        test("rejects a charset in the transfer encoding") {
            MimeContent.isSupportedEncoding("utf-8") shouldBe false
            MimeContent.isSupportedEncoding("iso-8859-1") shouldBe false
        }
    }

    context("charsetOf") {
        test("prefers the charset of the content type") {
            MimeContent.charsetOf(ContentType("text/plain; charset=iso-8859-1"), "utf-8") shouldBe Charsets.ISO_8859_1
        }

        test("falls back to the transfer encoding if the content type has no charset") {
            MimeContent.charsetOf(ContentType("text/plain"), "iso-8859-1") shouldBe Charsets.ISO_8859_1
        }

        test("falls back to utf-8 if neither names a usable charset") {
            MimeContent.charsetOf(ContentType("text/plain"), "definitely-not-a-charset") shouldBe Charsets.UTF_8
            MimeContent.charsetOf(null, null) shouldBe Charsets.UTF_8
        }

        test("maps mime charset names to java charsets") {
            MimeContent.charsetOf(ContentType("text/plain; charset=UTF8"), null) shouldBe Charsets.UTF_8
        }
    }

    context("of") {
        test("reads a part whose transfer encoding is a charset") {
            val part = part(
                "Content-Type: text/plain; charset=utf-8",
                "Content-Transfer-Encoding: utf-8",
                body = "Grüße"
            )

            // Regression: jakarta.mail throws "Unknown encoding: utf-8" here.
            shouldThrow<IOException> { part.content }
            MimeContent.of(part) shouldBe "Grüße"
        }

        test("uses the transfer encoding as charset if the content type has none") {
            val part = part(
                "Content-Type: text/plain",
                "Content-Transfer-Encoding: iso-8859-1",
                body = "Grüße".toByteArray(Charsets.ISO_8859_1)
            )

            MimeContent.of(part) shouldBe "Grüße"
        }

        test("reads html the same way") {
            val part = part(
                "Content-Type: text/html; charset=utf-8",
                "Content-Transfer-Encoding: utf-8",
                body = "<p>Grüße</p>"
            )

            MimeContent.of(part) shouldBe "<p>Grüße</p>"
        }

        test("still decodes base64") {
            val part = part(
                "Content-Type: text/plain; charset=utf-8",
                "Content-Transfer-Encoding: base64",
                body = Base64.encode("Grüße".toByteArray(Charsets.UTF_8))
            )

            MimeContent.of(part) shouldBe "Grüße"
        }

        test("still decodes quoted-printable") {
            val part = part(
                "Content-Type: text/plain; charset=utf-8",
                "Content-Transfer-Encoding: quoted-printable",
                body = "Gr=C3=BC=C3=9Fe"
            )

            MimeContent.of(part) shouldBe "Grüße"
        }

        test("reads a part without a transfer encoding") {
            val part = part("Content-Type: text/plain; charset=utf-8", body = "Grüße")

            MimeContent.of(part) shouldBe "Grüße"
        }

        test("does not swallow the error for non-text parts") {
            val part = part(
                "Content-Type: application/octet-stream",
                "Content-Transfer-Encoding: utf-8",
                body = "irrelevant"
            )

            shouldThrow<IOException> { MimeContent.of(part) }
        }
    }
})
