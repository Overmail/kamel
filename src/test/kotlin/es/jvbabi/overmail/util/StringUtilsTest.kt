package es.jvbabi.overmail.util

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class StringUtilsTest : FunSpec({

    context("substringAfterIgnoreCase") {
        test("removes a matching prefix") {
            "* SEARCH 1 2 3".substringAfterIgnoreCase("* SEARCH ") shouldBe "1 2 3"
        }

        test("ignores the case of the prefix") {
            "* search 1 2 3".substringAfterIgnoreCase("* SEARCH ") shouldBe "1 2 3"
            "* SEARCH 1 2 3".substringAfterIgnoreCase("* search ") shouldBe "1 2 3"
        }

        test("returns the input unchanged if the prefix does not match") {
            "A001 OK SEARCH".substringAfterIgnoreCase("* SEARCH ") shouldBe "A001 OK SEARCH"
        }

        test("only strips prefixes, not occurrences in the middle") {
            "x * SEARCH 1".substringAfterIgnoreCase("* SEARCH ") shouldBe "x * SEARCH 1"
        }

        test("returns an empty string if input and prefix are equal") {
            "LIST ".substringAfterIgnoreCase("LIST ") shouldBe ""
        }
    }

    context("sha1") {
        test("hashes the utf-8 bytes of the input as lowercase hex") {
            "abc".sha1() shouldBe "a9993e364706816aba3e25717850c26c9cd0d89d"
        }

        test("hashes an empty string") {
            "".sha1() shouldBe "da39a3ee5e6b4b0d3255bfef95601890afd80709"
        }

        test("hashes non-ascii input as utf-8") {
            "ü".sha1() shouldBe "94a759fd37735430753c7b6b80684306d80ea16e"
        }

        test("is stable for the same input") {
            "some mail fingerprint".sha1() shouldBe "some mail fingerprint".sha1()
        }
    }
})
