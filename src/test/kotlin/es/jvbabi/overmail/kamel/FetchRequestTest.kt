package es.jvbabi.overmail.kamel

import es.jvbabi.overmail.kamel.FetchRequest.Selection
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FetchRequestTest : FunSpec({

    context("selection") {
        test("requests all messages by default") {
            FetchRequest().selection shouldBe Selection.IdRange(1L, null)
        }

        test("getId selects a single id") {
            FetchRequest().apply { getId(6) }.selection shouldBe Selection.Ids(setOf(6L))
        }

        test("getIds selects the given ids and nothing between them") {
            FetchRequest().apply { getIds(listOf(9, 3, 5)) }.selection shouldBe Selection.Ids(setOf(3L, 5L, 9L))
        }

        test("getIds selects nothing for an empty list") {
            FetchRequest().apply { getIds(emptyList()) }.selection shouldBe Selection.Ids(emptySet())
        }

        test("idRange selects everything between two ids") {
            FetchRequest().apply { idRange(3, 9) }.selection shouldBe Selection.IdRange(3L, 9L)
        }

        test("getUid selects a single uid") {
            FetchRequest().apply { getUid(15201) }.selection shouldBe Selection.Uids(setOf(15201L))
        }

        test("uids selects the given uids") {
            FetchRequest().apply { uids(listOf(4, 9, 4)) }.selection shouldBe Selection.Uids(setOf(4L, 9L))
        }

        test("uidRange is open ended without an upper bound") {
            FetchRequest().apply { uidRange(4393) }.selection shouldBe Selection.UidRange(4393L, null)
            FetchRequest().apply { uidRange(4, 9) }.selection shouldBe Selection.UidRange(4L, 9L)
        }

        test("a range that runs backwards is refused") {
            shouldThrow<IllegalArgumentException> { FetchRequest().uidRange(9, 4) }
            shouldThrow<IllegalArgumentException> { FetchRequest().idRange(0) }
        }

        test("a uid range knows what lies in it") {
            (8L in Selection.UidRange(4, 9)) shouldBe true
            (10L in Selection.UidRange(4, 9)) shouldBe false
            (3L in Selection.UidRange(4, null)) shouldBe false
            (Long.MAX_VALUE in Selection.UidRange(4, null)) shouldBe true
        }

        test("getAll resets the selection to all messages") {
            FetchRequest().apply {
                getId(6)
                getAll()
            }.selection shouldBe Selection.IdRange(1L, null)
        }

        test("the last selection wins") {
            FetchRequest().apply {
                getId(6)
                getUid(42)
            }.selection shouldBe Selection.Uids(setOf(42L))
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
