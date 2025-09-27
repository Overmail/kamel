import com.overmail.core.ImapClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlin.use

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
            }.use { client ->
                client.login()
                client.getFolders()
                    .filter { it.fullName == "Archiv.Nachrichten" }
                    .forEach { folder ->
                        println(folder.fullName)
                        println()
                        folder.getMails {
                            envelope = true
                            flags = true
                            uid = true
                            getId(699)
                        }.forEach { email ->
                            println(email.uid.await().toString() + ": " + (email.subject.await() ?: "<no subject>"))
                            println("    From: " + email.from.await().joinToString(", ") { it.toString() })
                            println("    Sender: " + email.senders.await().joinToString(", ") { it.toString() })
                            println("    To: " + email.to.await().joinToString(", ") { it.toString() })
                            println("    Cc: " + email.cc.await().joinToString(", ") { it.toString() })
                            println("    Bcc: " + email.bcc.await().joinToString(", ") { it.toString() })
                            println("    Date: " + email.sentAt.await())
                            println("    Flags: " + email.flags.await().joinToString(", ") { it.value })
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