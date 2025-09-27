package com.overmail.core

import java.io.InputStream

/**
 * Repräsentiert den Inhalt einer E-Mail.
 * Bietet Streams für
 * - den Rohinhalt (RFC822)
 * - den Textinhalt (text/plain)
 * - den HTML-Inhalt (text/html)
 * sowie eine Liste von Anhängen inkl. Namen und Inhalts-Stream.
 */
data class EmailContent(
    /** Liefert bei jedem Aufruf einen neuen Stream mit dem kompletten RFC822-Rohinhalt. */
    val rawStream: suspend () -> InputStream,
    /** Optional: liefert einen Stream für den Textinhalt (text/plain). */
    val textStream: (() -> InputStream)? = null,
    /** Optional: liefert einen Stream für den HTML-Inhalt (text/html). */
    val htmlStream: (() -> InputStream)? = null,
    /** Lieferant für die Liste der Anhänge (on demand, um Speicher zu sparen). */
    val attachmentsSupplier: (suspend () -> List<EmailAttachment>) = { emptyList() }
)

/**
 * Repräsentiert einen Anhang einer E-Mail mit Anzeigenamen und Inhalt.
 * Der Inhalt wird on-demand per Stream geliefert.
 */
data class EmailAttachment(
    val name: String,
    val stream: suspend () -> InputStream
)
