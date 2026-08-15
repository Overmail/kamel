package es.jvbabi.overmail.parser

import es.jvbabi.overmail.core.FetchRequest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FetchCommandBuilderTest : FunSpec({

    test("requests all items in the documented order") {
        val request = FetchRequest().apply { all() }

        buildFetchCommand(request, 1, 5) shouldBe "FETCH 1:5 (FLAGS ENVELOPE UID)"
    }

    test("requests only the enabled items") {
        val request = FetchRequest().apply { envelope = true }

        buildFetchCommand(request, 3, 3) shouldBe "FETCH 3:3 (ENVELOPE)"
    }

    test("requests flags and uid without envelope") {
        val request = FetchRequest().apply {
            flags = true
            uid = true
        }

        buildFetchCommand(request, 1, 2) shouldBe "FETCH 1:2 (FLAGS UID)"
    }

    test("builds an empty item list if nothing is requested") {
        buildFetchCommand(FetchRequest(), 1, 1) shouldBe "FETCH 1:1 ()"
    }
})
