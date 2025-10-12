import com.overmail.core.ImapClient
import kotlinx.coroutines.*
import java.io.File

fun main() {
    runBlocking {
        try {
            ImapClient(
                host = "imap.mail.de",
                port = 993,
                ssl = true,
                debug = false,
                username = System.getenv("IMAP_USERNAME").orEmpty().ifBlank { throw MissingEnvVarException("IMAP_USERNAME") },
                password = System.getenv("IMAP_PASSWORD").orEmpty().ifBlank { throw MissingEnvVarException("IMAP_USERNAME") },
                coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            ).use { client ->
                client.testConnection()
                client
                    .getFolders(onlyRoot = false)
                    .first { it.fullName == "INBOX" }
                    .let { folder ->
                        println(folder.fullName)
                        folder.getMails {
                            getUid(6)
                            envelope = true
                            flags = true
                            uid = true
                            dumpMailOnError = true
                        }.forEach { email ->
                            println(email.subject.await())
                            println(email.flags.await())
                            val textFile = File("./out.txt")
                            val htmlFile = File("./out.html")
                            val rawFile = File("./out.eml")
                            textFile.delete()
                            htmlFile.delete()
                            rawFile.delete()
                            email.content.getContent(rawFile.outputStream(), textFile.outputStream(), htmlFile.outputStream())
                        }
                        println()
                    }

//                    .let { folder ->
//                        println(folder.fullName)
//                        println()
//
//                        val idleFolder = folder.getIdleFolder()
//                        launch {
//                            idleFolder.idle {
//                                onNewMessage {
//                                    println("NEW MESSAGE $it")
//                                }
//                            }
//                        }
//
//                        delay(60.seconds)
//                        idleFolder.cancel()
//
//                        return@let
//
//                        folder.getMails {
//                            envelope = true
//                            flags = true
//                            uid = true
//                            dumpMailOnError = true
//                            getUid(15201)
//                        }.forEach { email ->
//                            println(email.subject.await())
//
//                            val rawStream = object : OutputStream() {
//                                private val buffer = StringBuilder()
//                                override fun write(b: Int) {
//                                    if (b.toChar() == '\n') {
//                                        println(buffer.toString()) // Zeile ausgeben
//                                        buffer.clear()
//                                    } else {
//                                        buffer.append(b.toChar())
//                                    }
//                                }
//                            }
//
//                            val textStream = object : OutputStream() {
//                                private val buffer = StringBuilder()
//                                override fun write(b: Int) {
//                                    if (b.toChar() == '\n') {
//                                        println("TEXT: $buffer")
//                                        buffer.clear()
//                                    } else {
//                                        buffer.append(b.toChar())
//                                    }
//                                }
//                            }
//
//                            val htmlStream = object : OutputStream() {
//                                private val buffer = StringBuilder()
//                                override fun write(b: Int) {
//                                    if (b.toChar() == '\n') {
//                                        println("HTML: $buffer")
//                                        buffer.clear()
//                                    } else {
//                                        buffer.append(b.toChar())
//                                    }
//                                }
//                            }
//
//
//                            email.content.getContent(
//                                rawStream = rawStream,
//                                textStream = textStream,
//                                htmlStream = htmlStream
//                            )
//                            waitForEnter()
//                        }
//                        println("=".repeat(20))
//                    }
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