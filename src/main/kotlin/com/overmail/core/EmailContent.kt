package com.overmail.core

import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import java.io.OutputStream

class EmailContent(
    private val email: Email
) {
    fun getRawContent(): MutableSharedFlow<String> {
        val flow = MutableSharedFlow<String>()
        email.folder.client.coroutineScope.launch {
            val response = email.folder.getSocketInstance().execute("UID FETCH ${email.uid.await()} BODY[]")
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
    suspend fun getContent(
        rawStream: OutputStream,
        textStream: OutputStream,
        htmlStream: OutputStream,
    ) {
        val raw = getRawContent()
        var hasParsedHeadersForCurrentBoundary = false
        var currentPart = CurrentPart.None
        var currentBoundary: String? = null

        raw
            .takeWhile { it != "OVERMAIL_DONE" }
            .map { it.removePrefix("OVERMAIL_CONTENT: ") }
            .filterNot { it.startsWith("*") }
            .collect { line ->
                rawStream.write(line.toByteArray(Charsets.UTF_8))
                rawStream.write("\r\n".toByteArray(Charsets.UTF_8))
                rawStream.flush()
                if (line.startsWith("--")) {
                    currentBoundary = if (currentBoundary != null && line.endsWith("--")) {
                        hasParsedHeadersForCurrentBoundary = false
                        currentPart = CurrentPart.None
                        null
                    } else {
                        line.removePrefix("--").trim()
                    }
                }

                if (currentBoundary != null) {
                    if (!hasParsedHeadersForCurrentBoundary && line.isBlank()) {
                        hasParsedHeadersForCurrentBoundary = true
                        return@collect
                    }
                    if (!hasParsedHeadersForCurrentBoundary) {
                        if (line.startsWith("Content-Type:", ignoreCase = true)) {
                            val contentType = line.removePrefix("Content-Type:").trim()
                            if (contentType.startsWith("text/html")) {
                                currentPart = CurrentPart.Html
                            } else if (contentType.startsWith("text/plain")) {
                                currentPart = CurrentPart.Text
                            }
                        }
                    }

                    if (hasParsedHeadersForCurrentBoundary) {
                        when (currentPart) {
                            CurrentPart.Html -> htmlStream.write(line.toByteArray(Charsets.UTF_8))
                            CurrentPart.Text -> textStream.write(line.toByteArray(Charsets.UTF_8))
                            else -> Unit
                        }
                    }
                }
        }
    }

    private enum class CurrentPart {
        None,
        Html,
        Text
    }
}
