package com.overmail.util

import java.io.ByteArrayOutputStream
import kotlin.io.encoding.Base64

object MimeUtility {

    fun decode(payload: String): String {
        if (Regex("""\?=( )+=\?""").containsMatchIn(payload)) {
            // Multiple encoded words
            val parts = payload
                .split("=?")
                .map { it.trim() }
                .map { if (it.endsWith("?=")) "=?$it" else it }
                .filterNot { it.isBlank() }
            return parts.joinToString("") { decode(it) }
        }

        if (' ' in payload) {
            return payload
                .split(" ")
                .joinToString(" ") { decode(it) }
        }
        return when {
            payload.uppercase().startsWith("=?UTF-8?") -> decodeMime(payload, "UTF-8")
            payload.uppercase().startsWith("=?ISO-8859-1?") -> decodeMime(payload, "ISO-8859-1")
            payload.uppercase().startsWith("=?ISO-8859-15?") -> decodeMime(payload, "ISO-8859-15")
            else -> payload
        }
    }

    private fun decodeMime(payload: String, charset: String): String {
        val content = payload.substringAfterIgnoreCase("=?$charset?")
        return when {
            content.uppercase().startsWith("B?") -> {
                val base64Content = content.substringAfter("?").substringBeforeLast("?=")
                Base64.decode(base64Content).decodeToString()
            }
            content.uppercase().startsWith("Q?") -> {
                val qpContent = content.substringAfter("?").substringBeforeLast("?=")
                if (charset == "UTF-8") decodeQuotedPrintable(qpContent)
                else decodeQuotedPrintableIso(qpContent)
            }
            else -> payload
        }
    }

    private fun decodeQuotedPrintable(input: String): String {
        return decodeQuitedPrintable(input).toByteArray().toString(Charsets.UTF_8)
    }

    private fun decodeQuotedPrintableIso(input: String): String {
        return decodeQuitedPrintable(input).toByteArray().toString(Charsets.ISO_8859_1)
    }

    private fun decodeQuitedPrintable(input: String): ByteArrayOutputStream {
        val output = ByteArrayOutputStream()
        var i = 0
        while (i < input.length) {
            when (val c = input[i]) {
                '_' -> output.write(' '.code)
                '=' -> if (i + 2 < input.length) {
                    val hex = input.substring(i + 1, i + 3)
                    output.write(hex.toInt(16))
                    i += 2
                }
                else -> output.write(c.code)
            }
            i++
        }
        return output
    }

    private fun String.substringAfterIgnoreCase(delimiter: String): String {
        val index = this.uppercase().indexOf(delimiter.uppercase())
        return if (index >= 0) this.substring(index + delimiter.length) else this
    }
}
