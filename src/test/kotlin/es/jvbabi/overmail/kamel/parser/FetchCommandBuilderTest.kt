package es.jvbabi.overmail.kamel.parser

import es.jvbabi.overmail.kamel.FetchRequest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe

class FetchCommandBuilderTest : FunSpec({

    context("items") {
        test("requests all items in the documented order") {
            val request = FetchRequest().apply { all() }

            buildFetchCommands(request) shouldContainExactly listOf("FETCH 1:* (FLAGS ENVELOPE UID)")
        }

        test("requests only the enabled items") {
            val request = FetchRequest().apply {
                getId(3)
                envelope = true
            }

            buildFetchCommands(request) shouldContainExactly listOf("FETCH 3 (ENVELOPE)")
        }

        test("requests flags and uid without envelope") {
            val request = FetchRequest().apply {
                idRange(1, 2)
                flags = true
                uid = true
            }

            buildFetchCommands(request) shouldContainExactly listOf("FETCH 1:2 (FLAGS UID)")
        }

        test("builds an empty item list if nothing is requested") {
            buildFetchCommands(FetchRequest().apply { getId(1) }) shouldContainExactly listOf("FETCH 1 ()")
        }
    }

    context("selection") {
        test("getIds fetches exactly the given ids, not what lies between them") {
            val request = FetchRequest().apply {
                getIds(listOf(900, 3))
                uid = true
            }

            buildFetchCommands(request) shouldContainExactly listOf("FETCH 3,900 (UID)")
        }

        test("uidRange is sent as UID FETCH") {
            val request = FetchRequest().apply {
                uidRange(4, 9)
                uid = true
            }

            buildFetchCommands(request) shouldContainExactly listOf("UID FETCH 4:9 (UID)")
        }

        test("an open uidRange ends at *") {
            val request = FetchRequest().apply {
                uidRange(4393)
                uid = true
            }

            buildFetchCommands(request) shouldContainExactly listOf("UID FETCH 4393:* (UID)")
        }

        test("uids are sent as one set with neighbours joined to ranges") {
            val request = FetchRequest().apply {
                uids(listOf(13, 4, 15, 9, 12, 14))
                uid = true
            }

            buildFetchCommands(request) shouldContainExactly listOf("UID FETCH 4,9,12:15 (UID)")
        }

        test("an empty set sends nothing") {
            buildFetchCommands(FetchRequest().apply { uids(emptyList()) }).shouldBeEmpty()
            buildFetchCommands(FetchRequest().apply { getIds(emptyList()) }).shouldBeEmpty()
        }
    }

    context("the size of the folder") {
        test("ends an open range, since * is the last mail wherever the range starts") {
            buildFetchCommands(FetchRequest().apply { idRange(2); uid = true }, exists = 3) shouldContainExactly listOf("FETCH 2:3 (UID)")
        }

        test("cuts a range that reaches beyond it") {
            buildFetchCommands(FetchRequest().apply { idRange(2, 500); uid = true }, exists = 3) shouldContainExactly listOf("FETCH 2:3 (UID)")
            buildFetchCommands(FetchRequest().apply { idRange(1, 2); uid = true }, exists = 3) shouldContainExactly listOf("FETCH 1:2 (UID)")
        }

        test("sends nothing for a range behind it or for an empty folder") {
            buildFetchCommands(FetchRequest().apply { idRange(4) }, exists = 3).shouldBeEmpty()
            buildFetchCommands(FetchRequest().apply { getAll() }, exists = 0).shouldBeEmpty()
        }

        test("leaves out the ids behind it") {
            buildFetchCommands(FetchRequest().apply { getIds(listOf(2, 9)); uid = true }, exists = 3) shouldContainExactly listOf("FETCH 2 (UID)")
            buildFetchCommands(FetchRequest().apply { getId(9) }, exists = 3).shouldBeEmpty()
        }

        test("does not touch a selection by uid") {
            buildFetchCommands(FetchRequest().apply { uidRange(9); uid = true }, exists = 3) shouldContainExactly listOf("UID FETCH 9:* (UID)")
            buildFetchCommands(FetchRequest().apply { uids(listOf(9)); uid = true }, exists = 0) shouldContainExactly listOf("UID FETCH 9 (UID)")
        }
    }

    context("sequenceSets") {
        test("duplicates are sent once") {
            sequenceSets(listOf(7, 7, 8)) shouldContainExactly listOf("7:8")
        }

        test("a set too long for one command line is split between two numbers") {
            // No two of them are neighbours, so nothing collapses into a range.
            val uids = (1L..2000L).map { it * 2 }

            val sets = sequenceSets(uids, maxLength = 100)

            sets.forEach { it.length shouldBeLessThanOrEqual 100 }
            sets.joinToString(",").split(",").map { it.toLong() } shouldBe uids
        }
    }
})
