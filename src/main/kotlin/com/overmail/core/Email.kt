package com.overmail.core

import com.overmail.util.MimeUtility
import com.overmail.util.Optional
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlin.time.Instant

class EmailUser(
    val address: String,
    name: String?
) {
    val name = name
        ?.takeIf { it != "NIL" && it.isNotBlank() }
        ?.let {
            if (it.startsWith('"') && it.endsWith('"')) it.drop(1).dropLast(1)
            else it
        }
        ?.let { name -> MimeUtility.decode(name) }
        ?.let {
            if (it.startsWith("'") && it.endsWith("'")) it.drop(1).dropLast(1)
            else it
        }

    override fun toString(): String {
        if (name == null) return address
        return "$name <$address>"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EmailUser) return false
        if (address != other.address) return false
        if (name != other.name) return false
        return true
    }

    override fun hashCode(): Int {
        var result = address.hashCode()
        result = 31 * result + (name?.hashCode() ?: 0)
        return result
    }
}


@Suppress("unused")
class Email internal constructor(
    internal val folder: ImapFolder
) {
    val content = EmailContent(this)

    var subjectValue: Optional<String?> = Optional.Empty()
        internal set

    val subject: Deferred<String?>
        get() = folder.client.coroutineScope.async {
            this@Email.subjectValue.let { if (it is Optional.Set) return@async it.value }

            TODO("Use connection to download subject")
        }

    var sentAtValue: Optional<Instant> = Optional.Empty()
        internal set

    val sentAt: Deferred<Instant>
        get() = folder.client.coroutineScope.async {
            this@Email.sentAtValue.let { if (it is Optional.Set) return@async it.value }
            TODO("Use connection to download sentAt")
        }

    var sendersValue: Optional<Set<EmailUser>> = Optional.Empty()
        internal set

    val senders: Deferred<Set<EmailUser>>
        get() = folder.client.coroutineScope.async {
            this@Email.sendersValue.let { if (it is Optional.Set) return@async it.value }
            TODO("Use connection to download senders")
        }

    var fromValue: Optional<Set<EmailUser>> = Optional.Empty()
        internal set

    val from: Deferred<Set<EmailUser>>
        get() = folder.client.coroutineScope.async {
            this@Email.fromValue.let { if (it is Optional.Set) return@async it.value }
            TODO("Use connection to download from")
        }

    var replyToValue: Optional<Set<EmailUser>> = Optional.Empty()
        internal set

    val replyTo: Deferred<Set<EmailUser>>
        get() = folder.client.coroutineScope.async {
            this@Email.replyToValue.let { if (it is Optional.Set) return@async it.value }
            TODO("Use connection to download replyTo")
        }

    var toValue: Optional<Set<EmailUser>> = Optional.Empty()
        internal set

    val to: Deferred<Set<EmailUser>>
        get() = folder.client.coroutineScope.async {
            this@Email.toValue.let { if (it is Optional.Set) return@async it.value }
            TODO("Use connection to download to")
        }

    var ccValue: Optional<Set<EmailUser>> = Optional.Empty()
        internal set

    val cc: Deferred<Set<EmailUser>>
        get() = folder.client.coroutineScope.async {
            this@Email.ccValue.let { if (it is Optional.Set) return@async it.value }
            TODO("Verbindung verwenden, um cc herunterzuladen")
        }

    var bccValue: Optional<Set<EmailUser>> = Optional.Empty()
        internal set

    val bcc: Deferred<Set<EmailUser>>
        get() = folder.client.coroutineScope.async {
            this@Email.bccValue.let { if (it is Optional.Set) return@async it.value }
            TODO("Verbindung verwenden, um bcc herunterzuladen")
        }

    var messageIdValue: Optional<String> = Optional.Empty()
        internal set

    val messageId: Deferred<String>
        get() = folder.client.coroutineScope.async {
            this@Email.messageIdValue.let { if (it is Optional.Set) return@async it.value }
            TODO("Use connection to download messageId")
        }

    var inReplyToValue: Optional<String?> = Optional.Empty()
        internal set

    val inReplyTo: Deferred<String?>
        get() = folder.client.coroutineScope.async {
            this@Email.inReplyToValue.let { if (it is Optional.Set) return@async it.value }
            TODO("Use connection to download inReplyTo")
        }

    var uidValue: Optional<Long> = Optional.Empty()
        internal set

    val uid: Deferred<Long>
        get() = folder.client.coroutineScope.async {
            this@Email.uidValue.let { if (it is Optional.Set) return@async it.value }
            TODO("Use connection to download uid")
        }

    var flagsValue: Optional<Set<Flag>> = Optional.Empty()
        internal set

    val flags: Deferred<Set<Flag>>
        get() = folder.client.coroutineScope.async {
            this@Email.flagsValue.let { if (it is Optional.Set) return@async it.value }
            TODO("Use connection to download flags")
        }

    sealed class Flag {
        abstract val value: String

        data object Seen : Flag() {
            override val value = "\\Seen"
        }

        data object Answered : Flag() {
            override val value = "\\Answered"
        }

        data object Flagged : Flag() {
            override val value = "\\Flagged"
        }

        data object Deleted : Flag() {
            override val value = "\\Deleted"
        }

        data object Draft : Flag() {
            override val value = "\\Draft"
        }

        data object Recent : Flag() {
            override val value = "\\Recent"
        }

        data class Other(override val value: String) : Flag()

        companion object {
            fun fromString(value: String): Flag {
                return when (value) {
                    "\\Seen" -> Seen
                    "\\Answered" -> Answered
                    "\\Flagged" -> Flagged
                    "\\Deleted" -> Deleted
                    "\\Draft" -> Draft
                    "\\Recent" -> Recent
                    else -> Other(value)
                }
            }
        }
    }

    suspend fun print() {
        println("${this.uid.await()}: ${this.subject.await() ?: "<no subject>"}")
        println("    From: ${this.from.await().joinToString(", ") { it.toString() }}")
        println("    Sender: ${this.senders.await().joinToString(", ") { it.toString() }}")
        println("    To: ${this.to.await().joinToString(", ") { it.toString() }}")
        println("    Cc: ${this.cc.await().joinToString(", ") { it.toString() }}")
        println("    Bcc: ${this.bcc.await().joinToString(", ") { it.toString() }}")
        println("    Date: ${this.sentAt.await()}")
        println("    Flags: ${this.flags.await().joinToString(", ") { it.value }}")
        println("    In-Reply-To: ${this.inReplyTo.await() ?: "<none>"}")
        println("    Message-ID: ${this.messageId.await()}")
    }

    override fun toString(): String {
        return buildString {
            appendLine("${this@Email.uidValue}: ${this@Email.subjectValue}")
            appendLine("    From: ${this@Email.fromValue}")
            appendLine("    Sender: ${this@Email.sendersValue}")
            appendLine("    To: ${this@Email.toValue}")
            appendLine("    Cc: ${this@Email.ccValue}")
            appendLine("    Bcc: ${this@Email.bccValue}")
            appendLine("    Date: ${this@Email.sentAtValue}")
            appendLine("    Flags: ${this@Email.flagsValue}")
            appendLine("    In-Reply-To: ${this@Email.inReplyToValue}")
            appendLine("    Message-ID: ${this@Email.messageIdValue}")
        }
    }
}