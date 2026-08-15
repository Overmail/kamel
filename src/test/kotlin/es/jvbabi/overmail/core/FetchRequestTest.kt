package es.jvbabi.overmail.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FetchRequestTest : FunSpec({

    context("selection") {
        test("requests all messages by default") {
            FetchRequest().selection shouldBe FetchRequest.EmailSelection.Id(1L, Long.MAX_VALUE)
        }

        test("getId selects a single id") {
            FetchRequest().apply { getId(6) }.selection shouldBe FetchRequest.EmailSelection.Id(6L, 6L)
        }

        test("getIds selects the range between the smallest and largest id") {
            FetchRequest().apply { getIds(listOf(9, 3, 5)) }.selection shouldBe
                    FetchRequest.EmailSelection.Id(3L, 9L)
        }

        test("getIds falls back to 1:1 for an empty list") {
            FetchRequest().apply { getIds(emptyList()) }.selection shouldBe
                    FetchRequest.EmailSelection.Id(1L, 1L)
        }

        test("getUid selects a single uid") {
            FetchRequest().apply { getUid(15201) }.selection shouldBe FetchRequest.EmailSelection.Uid(15201L)
        }

        test("getAll resets the selection to all messages") {
            FetchRequest().apply {
                getId(6)
                getAll()
            }.selection shouldBe FetchRequest.EmailSelection.Id(1L, Long.MAX_VALUE)
        }

        test("the last selection wins") {
            FetchRequest().apply {
                getId(6)
                getUid(42)
            }.selection shouldBe FetchRequest.EmailSelection.Uid(42L)
        }
    }

    context("requested items") {
        test("nothing is requested by default") {
            val request = FetchRequest()

            request.flags shouldBe false
            request.envelope shouldBe false
            request.uid shouldBe false
            request.dumpMailOnError shouldBe false
        }

        test("all enables every item") {
            val request = FetchRequest().apply { all() }

            request.flags shouldBe true
            request.envelope shouldBe true
            request.uid shouldBe true
        }
    }
})
