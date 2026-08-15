package es.jvbabi.overmail.parser

import es.jvbabi.overmail.core.ImapFolder
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class FolderListParserTest : FunSpec({

    context("structure") {
        test("parses flags, delimiter and path") {
            val folder = FolderListParser.parse("""* LIST (\HasNoChildren) "/" "INBOX"""").shouldNotBeNull()

            folder.flags shouldBe listOf("""\HasNoChildren""")
            folder.delimiter shouldBe "/"
            folder.path shouldBe listOf("INBOX")
        }

        test("splits nested paths by the delimiter") {
            val folder = FolderListParser.parse("""* LIST (\HasChildren) "/" "INBOX/Projects/Kamel"""").shouldNotBeNull()

            folder.path shouldBe listOf("INBOX", "Projects", "Kamel")
        }

        test("supports a dot as delimiter") {
            val folder = FolderListParser.parse("""* LIST (\HasNoChildren) "." "INBOX.Archive"""").shouldNotBeNull()

            folder.delimiter shouldBe "."
            folder.path shouldBe listOf("INBOX", "Archive")
        }

        test("keeps spaces in folder names") {
            val folder = FolderListParser.parse("""* LIST (\HasNoChildren) "/" "Archive 2024"""").shouldNotBeNull()

            folder.path shouldBe listOf("Archive 2024")
        }

        test("parses multiple flags") {
            val folder = FolderListParser.parse("""* LIST (\HasNoChildren \Sent) "/" "Sent"""").shouldNotBeNull()

            folder.flags shouldBe listOf("""\HasNoChildren""", """\Sent""")
        }
    }

    context("special folder types") {
        test("INBOX is detected by its path") {
            FolderListParser.parse("""* LIST (\HasNoChildren) "/" "INBOX"""")!!
                .specialType shouldBe ImapFolder.SpecialType.INBOX
        }

        test("a nested folder named INBOX is not the inbox") {
            FolderListParser.parse("""* LIST (\HasNoChildren) "/" "Foo/INBOX"""")!!
                .specialType shouldBe null
        }

        test("\\Trash marks the trash folder") {
            FolderListParser.parse("""* LIST (\HasNoChildren \Trash) "/" "Papierkorb"""")!!
                .specialType shouldBe ImapFolder.SpecialType.TRASH
        }

        test("\\Junk marks the spam folder") {
            FolderListParser.parse("""* LIST (\HasNoChildren \Junk) "/" "Spam"""")!!
                .specialType shouldBe ImapFolder.SpecialType.SPAM
        }

        test("\\Sent marks the sent folder") {
            FolderListParser.parse("""* LIST (\HasNoChildren \Sent) "/" "Gesendet"""")!!
                .specialType shouldBe ImapFolder.SpecialType.SENT
        }

        test("\\Drafts marks the drafts folder") {
            FolderListParser.parse("""* LIST (\HasNoChildren \Drafts) "/" "Entwürfe"""")!!
                .specialType shouldBe ImapFolder.SpecialType.DRAFTS
        }

        test("\\Trash wins over \\Junk") {
            FolderListParser.parse("""* LIST (\Junk \Trash) "/" "Weg damit"""")!!
                .specialType shouldBe ImapFolder.SpecialType.TRASH
        }

        test("a regular folder has no special type") {
            FolderListParser.parse("""* LIST (\HasNoChildren) "/" "Newsletter"""")!!
                .specialType shouldBe null
        }
    }

    context("lines without a folder") {
        test("returns null for the tagged completion line") {
            FolderListParser.parse("A001 OK LIST completed") shouldBe null
        }

        test("returns null for a line without flags") {
            FolderListParser.parse("* SEARCH 1 2 3") shouldBe null
        }
    }
})
