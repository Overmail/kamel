package es.jvbabi.overmail.kamel.parser

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SearchResponseParserTest : FunSpec({

    test("parses a list of ids") {
        SearchResponseParser.parse("* SEARCH 1 2 3") shouldBe listOf(1L, 2L, 3L)
    }

    test("parses a single id") {
        SearchResponseParser.parse("* SEARCH 42") shouldBe listOf(42L)
    }

    test("parses an empty result") {
        SearchResponseParser.parse("* SEARCH") shouldBe emptyList()
    }

    test("ignores the case of the response") {
        SearchResponseParser.parse("* search 7") shouldBe listOf(7L)
    }

    test("skips tokens that are not numbers") {
        SearchResponseParser.parse("* SEARCH 1 abc 3") shouldBe listOf(1L, 3L)
    }

    test("parses UIDs above the range of an Int") {
        SearchResponseParser.parse("* SEARCH 4294967295") shouldBe listOf(4294967295L)
    }

    test("returns null for the tagged completion line") {
        SearchResponseParser.parse("A001 OK SEARCH completed") shouldBe null
    }

    test("returns null for an unrelated untagged response") {
        SearchResponseParser.parse("* 3 EXISTS") shouldBe null
    }
})
