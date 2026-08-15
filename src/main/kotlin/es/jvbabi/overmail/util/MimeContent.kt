package es.jvbabi.overmail.util

import jakarta.mail.internet.ContentType
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimePart
import jakarta.mail.internet.MimeUtility
import java.io.InputStream
import java.nio.charset.Charset

/**
 * Reads the content of a MIME part, including parts whose `Content-Transfer-Encoding` jakarta.mail
 * refuses to decode.
 */
internal object MimeContent {

    /**
     * Transfer encodings jakarta.mail can decode.
     *
     * @see <a href="https://www.rfc-editor.org/rfc/rfc2045#section-6">RFC 2045 - 6. Content-Transfer-Encoding Header Field</a>
     */
    private val supportedEncodings = setOf(
        "7bit", "8bit", "binary", "base64", "quoted-printable",
        "uuencode", "x-uuencode", "x-uue"
    )

    /**
     * Returns `true` if [encoding] can be handled by jakarta.mail. A missing encoding means the
     * part is not encoded at all.
     */
    fun isSupportedEncoding(encoding: String?): Boolean {
        val encoding = encoding?.trim()?.lowercase() ?: return true
        return encoding.isEmpty() || encoding in supportedEncodings
    }

    /**
     * Charset to read a part with that carries an unsupported [encoding]. Senders producing such
     * parts usually put the charset into `Content-Transfer-Encoding`, so it is used whenever
     * `Content-Type` does not name one.
     */
    fun charsetOf(contentType: ContentType?, encoding: String?): Charset {
        return contentType?.getParameter("charset")?.toCharsetOrNull()
            ?: encoding?.toCharsetOrNull()
            ?: Charsets.UTF_8
    }

    /**
     * Returns the content of [part]. An unsupported `Content-Transfer-Encoding` - `utf-8` and other
     * charsets are common - makes jakarta.mail throw `IOException: Unknown encoding: ...` instead
     * of returning the body. Such bodies are not encoded, so the raw bytes are read directly.
     */
    fun of(part: MimePart): Any {
        val encoding = runCatching { part.encoding }.getOrNull()
        if (isSupportedEncoding(encoding)) return part.content

        val contentType = runCatching { ContentType(part.contentType) }.getOrNull()
        // Only text can be recovered this way; for anything else there is no sensible fallback.
        if (contentType != null && !contentType.match("text/*")) return part.content

        val raw = part.rawInputStream() ?: return part.content
        return raw.use { it.readBytes() }.toString(charsetOf(contentType, encoding))
    }

    /**
     * The undecoded content of the part. `getRawInputStream` is not part of the [MimePart]
     * interface, so it is only available on the two implementations that declare it.
     */
    private fun MimePart.rawInputStream(): InputStream? = when (this) {
        is MimeBodyPart -> this.rawInputStream
        is MimeMessage -> this.rawInputStream
        else -> null
    }

    private fun String.toCharsetOrNull(): Charset? = runCatching {
        val name = MimeUtility.javaCharset(this.trim())
        if (Charset.isSupported(name)) Charset.forName(name) else null
    }.getOrNull()
}
