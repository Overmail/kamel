package es.jvbabi.overmail.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

class FlagTest : FunSpec({

    context("fromString maps the system flags") {
        withData(
            nameFn = { (raw, _) -> raw },
            "\\Seen" to Email.Flag.Seen,
            "\\Answered" to Email.Flag.Answered,
            "\\Flagged" to Email.Flag.Flagged,
            "\\Deleted" to Email.Flag.Deleted,
            "\\Draft" to Email.Flag.Draft,
            "\\Recent" to Email.Flag.Recent
        ) { (raw, expected) ->
            Email.Flag.fromString(raw) shouldBe expected
            expected.value shouldBe raw
        }
    }

    context("fromString maps everything else to Other") {
        withData("\\Junk", "\$MDNSent", "NonJunk", "\\seen") { raw ->
            Email.Flag.fromString(raw) shouldBe Email.Flag.Other(raw)
        }
    }

    test("Other keeps the raw value") {
        Email.Flag.fromString("\\Junk").value shouldBe "\\Junk"
    }
})
