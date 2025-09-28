import com.overmail.core.ImapClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.io.OutputStream

fun main() {
    runBlocking {
        try {
            ImapClient {
                host = "imap.strato.de"
                port = 993
                ssl = true
                auth = true
                username = System.getenv("IMAP_USERNAME").orEmpty().ifBlank { throw MissingEnvVarException("IMAP_USERNAME") }
                password = System.getenv("IMAP_PASSWORD").orEmpty().ifBlank { throw MissingEnvVarException("IMAP_USERNAME") }
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                debug = false
            }.use { client ->
                client.login()
                client.getFolders()
                    .filter { it.fullName == "INBOX" }
                    .forEach { folder ->
                        println(folder.fullName)
                        println()
                        folder.getMails {
                            envelope = true
                            flags = true
                            uid = true
                            dumpMailOnError = true
                            getUid(15201)
                        }.forEach { email ->
                            println(email.subject.await())

                            val rawStream = object : OutputStream() {
                                private val buffer = StringBuilder()
                                override fun write(b: Int) {
                                    if (b.toChar() == '\n') {
                                        println(buffer.toString()) // Zeile ausgeben
                                        buffer.clear()
                                    } else {
                                        buffer.append(b.toChar())
                                    }
                                }
                            }

                            val textStream = object : OutputStream() {
                                private val buffer = StringBuilder()
                                override fun write(b: Int) {
                                    if (b.toChar() == '\n') {
                                        println("TEXT: $buffer")
                                        buffer.clear()
                                    } else {
                                        buffer.append(b.toChar())
                                    }
                                }
                            }

                            val htmlStream = object : OutputStream() {
                                private val buffer = StringBuilder()
                                override fun write(b: Int) {
                                    if (b.toChar() == '\n') {
                                        println("HTML: $buffer")
                                        buffer.clear()
                                    } else {
                                        buffer.append(b.toChar())
                                    }
                                }
                            }


                            email.content.getContent(
                                rawStream = rawStream,
                                textStream = textStream,
                                htmlStream = htmlStream
                            )
                            waitForEnter()
                        }
                        println("=".repeat(20))
                    }
            }
        } catch (e: MissingEnvVarException) {
            e.printStackTrace()
        }
    }
}

private class MissingEnvVarException(envVar: String) : Exception("Environment variable $envVar is missing or blank")

private fun waitForEnter(message: String = "Press ENTER to continue...") {
    println(message)
    readlnOrNull()
}