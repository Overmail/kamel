package es.jvbabi.overmail.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class EmailUserTest : FunSpec({

    context("name normalization") {
        test("keeps a plain name") {
            EmailUser("jane@example.org", "Jane Doe").name shouldBe "Jane Doe"
        }

        test("strips surrounding double quotes") {
            EmailUser("jane@example.org", "\"Jane Doe\"").name shouldBe "Jane Doe"
        }

        test("strips surrounding single quotes") {
            EmailUser("jane@example.org", "'Jane Doe'").name shouldBe "Jane Doe"
        }

        test("strips double quotes before single quotes") {
            EmailUser("jane@example.org", "\"'Jane Doe'\"").name shouldBe "Jane Doe"
        }

        test("decodes mime encoded names") {
            EmailUser("jane@example.org", "\"=?UTF-8?B?R3LDvMOfZQ==?=\"").name shouldBe "Grüße"
        }

        test("literal NIL becomes null") {
            EmailUser("jane@example.org", "NIL").name shouldBe null
        }

        test("null stays null") {
            EmailUser("jane@example.org", null).name shouldBe null
        }

        test("blank name becomes null") {
            EmailUser("jane@example.org", "   ").name shouldBe null
        }

        test("empty quotes become an empty name") {
            EmailUser("jane@example.org", "\"\"").name shouldBe ""
        }
    }

    context("toString") {
        test("renders name and address") {
            EmailUser("jane@example.org", "Jane Doe").toString() shouldBe "Jane Doe <jane@example.org>"
        }

        test("renders only the address when there is no name") {
            EmailUser("jane@example.org", null).toString() shouldBe "jane@example.org"
        }
    }

    context("equality") {
        test("same address and name are equal") {
            EmailUser("jane@example.org", "Jane") shouldBe EmailUser("jane@example.org", "Jane")
            EmailUser("jane@example.org", "Jane").hashCode() shouldBe EmailUser("jane@example.org", "Jane").hashCode()
        }

        test("quoted and unquoted names are equal after normalization") {
            EmailUser("jane@example.org", "\"Jane\"") shouldBe EmailUser("jane@example.org", "Jane")
        }

        test("different addresses are not equal") {
            EmailUser("jane@example.org", "Jane") shouldNotBe EmailUser("john@example.org", "Jane")
        }

        test("different names are not equal") {
            EmailUser("jane@example.org", "Jane") shouldNotBe EmailUser("jane@example.org", null)
        }

        test("emails are deduplicated in a set") {
            setOf(
                EmailUser("jane@example.org", "\"Jane\""),
                EmailUser("jane@example.org", "Jane")
            ).size shouldBe 1
        }
    }
})
