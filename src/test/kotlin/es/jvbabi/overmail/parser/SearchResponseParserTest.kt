package es.jvbabi.overmail.parser

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SearchResponseParserTest : FunSpec({

    test("parses a list of ids") {
        SearchResponseParser.parseIds("* SEARCH 1 2 3") shouldBe listOf(1, 2, 3)
    }

    test("parses a single id") {
        SearchResponseParser.parseIds("* SEARCH 42") shouldBe listOf(42)
    }

    test("parses an empty result") {
        SearchResponseParser.parseIds("* SEARCH") shouldBe emptyList()
    }

    test("ignores the case of the response") {
        SearchResponseParser.parseIds("* search 7") shouldBe listOf(7)
    }

    test("skips tokens that are not numbers") {
        SearchResponseParser.parseIds("* SEARCH 1 abc 3") shouldBe listOf(1, 3)
    }

    test("returns null for the tagged completion line") {
        SearchResponseParser.parseIds("A001 OK SEARCH completed") shouldBe null
    }

    test("returns null for an unrelated untagged response") {
        SearchResponseParser.parseIds("* 3 EXISTS") shouldBe null
    }
})
