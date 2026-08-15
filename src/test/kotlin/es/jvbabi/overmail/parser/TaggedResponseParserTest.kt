package es.jvbabi.overmail.parser

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TaggedResponseParserTest : FunSpec({

    test("parses a tagged OK") {
        TaggedResponseParser.parse("A001 OK SELECT completed", "A001") shouldBe ImapStatus.OK
    }

    test("parses a tagged NO") {
        TaggedResponseParser.parse("A001 NO Mailbox does not exist", "A001") shouldBe ImapStatus.NO
    }

    test("parses a tagged BAD") {
        TaggedResponseParser.parse(
            "A001 BAD Error in IMAP command SELECT: Too many arguments",
            "A001"
        ) shouldBe ImapStatus.BAD
    }

    test("parses a completion without a message") {
        TaggedResponseParser.parse("A001 OK", "A001") shouldBe ImapStatus.OK
    }

    test("ignores the case of the status") {
        TaggedResponseParser.parse("A001 ok SELECT completed", "A001") shouldBe ImapStatus.OK
    }

    test("returns null for untagged responses") {
        TaggedResponseParser.parse("* 3 EXISTS", "A001") shouldBe null
        TaggedResponseParser.parse("* OK [UIDVALIDITY 1] UIDs valid", "A001") shouldBe null
    }

    test("returns null for the completion of another command") {
        TaggedResponseParser.parse("A002 OK SELECT completed", "A001") shouldBe null
    }

    test("returns null if the tag is only a prefix of the line's tag") {
        TaggedResponseParser.parse("A0011 OK SELECT completed", "A001") shouldBe null
    }

    test("returns null for a line that starts with the tag but carries no status") {
        TaggedResponseParser.parse("A001 SEARCH 1 2 3", "A001") shouldBe null
    }
})
