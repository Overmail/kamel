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
                    .filter { it.fullName == "INBOX" }
                    .forEach { folder ->
                        println(folder.fullName)
                        println()
                        folder.getMails {
                            envelope = true
                            flags = true
                            uid = true
                            dumpMailOnError = true
                            getAll()
                        }.forEach { email ->
                            email.print()
                            email.content.await().htmlStream!!().readBytes().toString(Charsets.UTF_8).let { println(it) }
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