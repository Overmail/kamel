package es.jvbabi.overmail.parser

import es.jvbabi.overmail.core.ImapFolder
import es.jvbabi.overmail.util.substringAfterIgnoreCase

internal data class ParsedFolder(
    val flags: List<String>,
    val delimiter: String,
    val path: List<String>,
    val specialType: ImapFolder.SpecialType?
)

/**
 * Parses untagged `LIST` responses, e.g. `* LIST (\HasNoChildren) "/" "INBOX"`.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc3501#section-7.2.2">RFC 3501 - 7.2.2. LIST Response</a>
 */
internal object FolderListParser {

    private val FOLDER_REGEX = Regex("""\((.*?)\)\s+"(.*?)"\s+(.*)""")

    /**
     * Returns the folder described by [line], or `null` if [line] does not describe a folder.
     */
    fun parse(line: String): ParsedFolder? {
        val data = line.substringAfterIgnoreCase("LIST ")
        val match = FOLDER_REGEX.find(data) ?: return null

        val flags = match.groupValues[1].split(" ").map { it.trim() }
        val delimiter = match.groupValues[2]
        val path = match.groupValues[3].trim('"').split(delimiter)

        val specialType = if (flags.contains("\\Trash")) ImapFolder.SpecialType.TRASH
        else if (flags.contains("\\Junk")) ImapFolder.SpecialType.SPAM
        else if (flags.contains("\\Sent")) ImapFolder.SpecialType.SENT
        else if (flags.contains("\\Drafts")) ImapFolder.SpecialType.DRAFTS
        else if (path.size == 1 && path[0] == "INBOX") ImapFolder.SpecialType.INBOX
        else null

        return ParsedFolder(flags = flags, delimiter = delimiter, path = path, specialType = specialType)
    }
}
