package es.jvbabi.overmail.kamel.parser

import es.jvbabi.overmail.kamel.FolderState
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class StatusResponseParserTest : FunSpec({

    test("reads the attributes in whatever order the server sends them") {
        StatusResponseParser.parse(listOf("""* STATUS "INBOX" (UIDVALIDITY 3857529045 MESSAGES 231 UIDNEXT 44292)""")) shouldBe
                FolderState(uidValidity = 3857529045, uidNext = 44292, exists = 231)
    }

    test("has no UIDNEXT if the server leaves it out") {
        StatusResponseParser.parse(listOf("* STATUS INBOX (MESSAGES 0 UIDVALIDITY 1)")) shouldBe
                FolderState(uidValidity = 1, uidNext = null, exists = 0)
    }

    test("is not confused by parens in the folder name") {
        StatusResponseParser.parse(listOf("""* STATUS "Archive (2025)" (MESSAGES 3 UIDVALIDITY 9)""")) shouldBe
                FolderState(uidValidity = 9, uidNext = null, exists = 3)
    }

    test("reads the attributes after a folder name sent as a literal") {
        StatusResponseParser.parse(listOf("* STATUS {5}", "Hello", " (MESSAGES 3 UIDVALIDITY 9 UIDNEXT 4)")) shouldBe
                FolderState(uidValidity = 9, uidNext = 4, exists = 3)
    }

    test("ignores other responses") {
        StatusResponseParser.parse(listOf("* 3 EXISTS")) shouldBe null
    }

    test("fails if the server names no UIDVALIDITY") {
        shouldThrow<IllegalStateException> { StatusResponseParser.parse(listOf("* STATUS INBOX (MESSAGES 3)")) }
    }
})
