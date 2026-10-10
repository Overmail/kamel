package es.jvbabi.overmail.kamel.parser

import es.jvbabi.overmail.kamel.FolderState
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class SelectResponseParserTest : FunSpec({

    fun parse(vararg lines: String) = SelectResponseParser().apply { lines.forEach(::add) }.state("INBOX")

    test("collects the state from the untagged responses") {
        parse(
            "* 172 EXISTS",
            "* 1 RECENT",
            "* OK [UNSEEN 12] Message 12 is first unseen",
            "* OK [UIDVALIDITY 3857529045] UIDs valid",
            "* OK [UIDNEXT 4392] Predicted next UID",
        ) shouldBe FolderState(uidValidity = 3857529045, uidNext = 4392, exists = 172)
    }

    test("reads the response codes whatever their case") {
        parse("* 3 exists", "* ok [uidvalidity 5] valid") shouldBe FolderState(uidValidity = 5, uidNext = null, exists = 3)
    }

    test("does not take RECENT for the number of messages") {
        parse("* 4 RECENT", "* 9 EXISTS", "* OK [UIDVALIDITY 1]").exists shouldBe 9
    }

    test("a later EXISTS replaces an earlier one") {
        parse("* 9 EXISTS", "* OK [UIDVALIDITY 1]", "* 10 EXISTS").exists shouldBe 10
    }

    test("fails if the server names no UIDVALIDITY") {
        shouldThrow<IllegalStateException> { parse("* 9 EXISTS") }.message shouldContain "UIDVALIDITY"
    }
})
