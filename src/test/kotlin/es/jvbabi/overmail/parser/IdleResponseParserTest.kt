package es.jvbabi.overmail.parser

import es.jvbabi.overmail.core.Email
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class IdleResponseParserTest : FunSpec({

    context("EXISTS") {
        test("reports a new message") {
            IdleResponseParser.parse("* 5 EXISTS") shouldBe IdleEvent.NewMessage(5)
        }

        test("tolerates trailing whitespace") {
            IdleResponseParser.parse("* 12 EXISTS\r") shouldBe IdleEvent.NewMessage(12)
        }
    }

    context("EXPUNGE") {
        test("reports a removed message") {
            IdleResponseParser.parse("* 3 EXPUNGE") shouldBe IdleEvent.RemovedMessage(3)
        }
    }

    context("FETCH FLAGS") {
        test("reports changed flags") {
            IdleResponseParser.parse("""* 5 FETCH (FLAGS (\Seen \Flagged))""") shouldBe
                    IdleEvent.FlagsChanged(5, listOf(Email.Flag.Seen, Email.Flag.Flagged))
        }

        test("reports cleared flags") {
            IdleResponseParser.parse("* 5 FETCH (FLAGS ())") shouldBe IdleEvent.FlagsChanged(5, emptyList())
        }

        test("keeps unknown flags") {
            IdleResponseParser.parse("""* 8 FETCH (FLAGS (\Seen ${'$'}Forwarded))""") shouldBe
                    IdleEvent.FlagsChanged(8, listOf(Email.Flag.Seen, Email.Flag.Other("\$Forwarded")))
        }

        test("ignores items after the flags") {
            IdleResponseParser.parse("""* 5 FETCH (FLAGS (\Seen) UID 42)""") shouldBe
                    IdleEvent.FlagsChanged(5, listOf(Email.Flag.Seen))
        }
    }

    context("lines without an event") {
        test("ignores the untagged OK line") {
            IdleResponseParser.parse("* OK Still here") shouldBe null
        }

        test("ignores the continuation request") {
            IdleResponseParser.parse("+ idling") shouldBe null
        }

        test("ignores the tagged completion line") {
            IdleResponseParser.parse("A001 OK IDLE terminated") shouldBe null
        }

        test("ignores a FETCH response without flags") {
            IdleResponseParser.parse("* 5 FETCH (UID 42)") shouldBe null
        }

        test("ignores RECENT") {
            IdleResponseParser.parse("* 5 RECENT") shouldBe null
        }
    }
})
