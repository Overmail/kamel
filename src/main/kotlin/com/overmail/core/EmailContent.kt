package com.overmail.core

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch

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
    fun getContent(
        useRaw: Boolean = false,
        useHtml: Boolean = false,
        useText: Boolean = false
    ): Content {
        val raw = getRawContent()
        val rawChannel = Channel<String>(capacity = Channel.BUFFERED)
        val textChannel = Channel<String>(capacity = Channel.BUFFERED)
        val htmlChannel = Channel<String>(capacity = Channel.BUFFERED)
        var hasParsedHeadersForCurrentBoundary = false
        var currentPart = CurrentPart.None
        var currentBoundary: String? = null

        var hasText = false
        var hasHtml = false

        email.folder.client.coroutineScope.launch {
            raw
                .takeWhile { it != "OVERMAIL_DONE" }
                .map { it.removePrefix("OVERMAIL_CONTENT: ") }
                .filterNot { it.startsWith("*") }
                .collect { line ->
                    if (useRaw) rawChannel.send(line)
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
                                CurrentPart.Html -> {
                                    hasHtml = true
                                    if (useHtml) htmlChannel.send(line)
                                }
                                CurrentPart.Text -> {
                                    hasText = true
                                    if (useText) textChannel.send(line)
                                }
                                else -> if (useRaw) rawChannel.send(line)
                            }
                        }
                    }
                }
            rawChannel.close()
            textChannel.close()
            htmlChannel.close()
        }

        return Content(
            rawStream = rawChannel,
            text = if (hasText) textChannel else null,
            html = if (hasHtml) htmlChannel else null
        )
    }

    private enum class CurrentPart {
        None,
        Html,
        Text
    }
}

data class Content(
    val rawStream: Channel<String>,
    val text: Channel<String>?,
    val html: Channel<String>?
)