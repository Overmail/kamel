package com.overmail.core

import jakarta.mail.BodyPart
import jakarta.mail.Multipart
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.*

class EmailContent(
    private val email: Email
) {
    fun getRawContent(): MutableSharedFlow<String> {
        val flow = MutableSharedFlow<String>()
        email.folder.imapClient.coroutineScope.launch {
            val response = email.folder.getClient().execute("UID FETCH ${email.uid.await()} BODY[]")
            response.response.consumeEach {
                flow.emit("OVERMAIL_CONTENT: $it")
            }
            flow.emit("OVERMAIL_DONE")
        }
        return flow
    }

    /**
     * Use the parameters to determine which parts of the email content you want to retrieve.
     * If you try to access a part that is not requested, the method might hang indefinitely
     * since the requested stream is not being consumed.
     */
    fun getContent(
        rawStream: OutputStream,
        textStream: OutputStream,
        htmlStream: OutputStream,
    ) {
        val raw = getRawContent()

        val pipeIn = PipedInputStream()
        val pipeOut = PipedOutputStream(pipeIn)

        email.folder.imapClient.coroutineScope.launch {
            raw
                .takeWhile { it != "OVERMAIL_DONE" }
                .map { it.removePrefix("OVERMAIL_CONTENT: ") }
                .filterNot { it.startsWith("*") }
                .collect {
                    val line = it + "\r\n"
                    val bytes = line.toByteArray(Charsets.UTF_8)
                    pipeOut.write(bytes)
                    rawStream.write(bytes)
                }
            pipeOut.close()
        }

        println("vor einlesen")
        val message = MimeMessage(Session.getDefaultInstance(Properties()), pipeIn)
        println("nach einlesen")

        fun handlePart(part: Any, depth: Int = 0) {
            val indent = "  ".repeat(depth)
            println("${indent}handlePart: ${part::class.simpleName}")
            when (part) {
                is String -> {
                    println("${indent}String part: ${part.take(40)}...")
                    textStream.write(part.toByteArray())
                }

                is Multipart -> {
                    println("${indent}Multipart part with ${part.count} body parts")
                    for (i in 0 until part.count) {
                        println("${indent}Handling body part $i")
                        handlePart(part.getBodyPart(i), depth + 1)
                    }
                }

                is BodyPart -> {
                    val disposition = part.disposition?.lowercase()
                    println("${indent}BodyPart disposition: $disposition")
                    if (disposition != null && disposition == "attachment") {
                        println("${indent}Skipping attachment")
                        return
                    }

                    val contentType = part.contentType.lowercase()
                    println("${indent}BodyPart contentType: $contentType")
                    val content = part.content

                    when {
                        contentType.contains("text/plain") && content is String -> {
                            println("${indent}Text/plain content")
                            textStream.write(content.toByteArray())
                        }

                        contentType.contains("text/html") && content is String -> {
                            println("${indent}Text/html content")
                            htmlStream.write(content.toByteArray())
                        }

                        content is Multipart -> {
                            println("${indent}Nested Multipart content")
                            handlePart(content, depth + 1)
                        }

                        content is BodyPart -> {
                            println("${indent}Nested BodyPart content")
                            handlePart(content, depth + 1)
                        }
                    }
                }
            }
        }

        val content = message.content
        handlePart(content)
    }
}
