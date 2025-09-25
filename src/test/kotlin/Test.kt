import com.overmail.core.ImapClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlin.use

fun main() {
    runBlocking {
        ImapClient {
            host = "imap.mail.de"
            port = 993
            ssl = true
            auth = true
            username = "overmail.testaccount@mail.de"
            password = "_LLjmgUt9Vp2baZBYIeSJKO6q26RT8GJa487NOIHE"
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }.use { client ->
            client.login()
            client.getFolders()
                .filter { it.name == "INBOX" }
                .forEach { folder ->
                    println(folder.name)
                    println()
                    folder.getMails {
                        envelope = true
                        flags = true
                        getAll()
                    }.forEach { email ->
                        println("${email.from.await().joinToString()} (reply to ${email.replyTo.await().joinToString()}) -> ${email.to.await().joinToString()} ${email.sentAt.await().epochSeconds} ${email.subject.await()}")
                    }
                    println("=".repeat(20))
                }
        }
    }
}