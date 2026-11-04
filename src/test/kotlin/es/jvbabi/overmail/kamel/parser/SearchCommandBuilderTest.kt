package es.jvbabi.overmail.kamel.parser

import es.jvbabi.overmail.kamel.ImapCommand
import es.jvbabi.overmail.kamel.SearchRequest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.LocalDate

private fun command(criteria: SearchRequest.() -> Unit): ImapCommand =
    buildSearchCommand(SearchRequest().apply(criteria).criterion)

/** The command as one string, for commands without literals. */
private fun line(criteria: SearchRequest.() -> Unit): String {
    val command = command(criteria)
    command.parts.forEach { it.shouldBeInstanceOf<ImapCommand.Part.Text>() }
    return command.toString()
}

class SearchCommandBuilderTest : FunSpec({

    test("a search without criteria finds everything") {
        line { } shouldBe "UID SEARCH ALL"
    }

    test("a subject is sent as a quoted string") {
        line { subject("Lunch tomorrow") } shouldBe """UID SEARCH SUBJECT "Lunch tomorrow""""
    }

    test("quotes and backslashes in a string are escaped") {
        line { subject("""say "hi" \o/""") } shouldBe """UID SEARCH SUBJECT "say \"hi\" \\o/""""
    }

    test("an empty string is sent as an empty quoted string") {
        line { header("X-Spam", "") } shouldBe """UID SEARCH HEADER "X-Spam" """""
    }

    test("a header search names the field and the text") {
        line { header("List-Id", "kotlin") } shouldBe """UID SEARCH HEADER "List-Id" "kotlin""""
    }

    test("messageId searches the Message-ID header") {
        line { messageId("<x@example.org>") } shouldBe """UID SEARCH HEADER "Message-ID" "<x@example.org>""""
    }

    test("dates are written as SEARCH takes them") {
        line {
            sentOn(LocalDate(2025, 5, 5))
            sentSince(LocalDate(2024, 12, 31))
            sentBefore(LocalDate(2026, 1, 10))
        } shouldBe "UID SEARCH SENTON 5-May-2025 SENTSINCE 31-Dec-2024 SENTBEFORE 10-Jan-2026"
    }

    test("the internal date is searched with ON, SINCE and BEFORE") {
        line {
            receivedOn(LocalDate(2025, 2, 1))
            receivedSince(LocalDate(2025, 3, 9))
            receivedBefore(LocalDate(2025, 11, 30))
        } shouldBe "UID SEARCH ON 1-Feb-2025 SINCE 9-Mar-2025 BEFORE 30-Nov-2025"
    }

    test("criteria next to each other are combined with AND") {
        line {
            subject("Lunch")
            sentOn(LocalDate(2025, 5, 5))
        } shouldBe """UID SEARCH SUBJECT "Lunch" SENTON 5-May-2025"""
    }

    context("or") {
        test("of two criteria is one OR") {
            line {
                or {
                    subject("Lunch")
                    subject("Dinner")
                }
            } shouldBe """UID SEARCH OR SUBJECT "Lunch" SUBJECT "Dinner""""
        }

        test("of more than two criteria nests ORs") {
            line {
                or {
                    subject("a")
                    subject("b")
                    subject("c")
                }
            } shouldBe """UID SEARCH OR SUBJECT "a" OR SUBJECT "b" SUBJECT "c""""
        }

        test("of one criterion is that criterion") {
            line { or { subject("a") } } shouldBe """UID SEARCH SUBJECT "a""""
        }

        test("groups the criteria of an and in parentheses") {
            line {
                or {
                    and {
                        subject("Lunch")
                        sentOn(LocalDate(2025, 5, 5))
                    }
                    messageId("x@example.org")
                }
            } shouldBe """UID SEARCH OR (SUBJECT "Lunch" SENTON 5-May-2025) HEADER "Message-ID" "x@example.org""""
        }
    }

    context("not") {
        test("of one criterion negates it") {
            line { not { subject("Spam") } } shouldBe """UID SEARCH NOT SUBJECT "Spam""""
        }

        test("of several criteria negates them together") {
            line {
                not {
                    subject("Spam")
                    header("X-Spam", "yes")
                }
            } shouldBe """UID SEARCH NOT (SUBJECT "Spam" HEADER "X-Spam" "yes")"""
        }

        test("of an or finds none of them") {
            line {
                not {
                    or {
                        subject("a")
                        subject("b")
                    }
                }
            } shouldBe """UID SEARCH NOT OR SUBJECT "a" SUBJECT "b""""
        }
    }

    context("a string that is not ASCII") {
        test("is sent as a literal, under CHARSET UTF-8") {
            val command = command { subject("Grüße") }

            command.parts shouldHaveSize 2
            (command.parts[0] as ImapCommand.Part.Text).text shouldBe "UID SEARCH CHARSET UTF-8 SUBJECT "
            // The size of a literal counts bytes: ü and ß take two each.
            (command.parts[1] as ImapCommand.Part.Literal).bytes shouldBe "Grüße".toByteArray(Charsets.UTF_8)
            command.toString() shouldBe "UID SEARCH CHARSET UTF-8 SUBJECT {7}Grüße"
        }

        test("leaves the strings around it quoted") {
            val command = command {
                subject("Grüße")
                header("X-Mailer", "kamel")
            }

            command.parts shouldHaveSize 3
            (command.parts[2] as ImapCommand.Part.Text).text shouldBe """ HEADER "X-Mailer" "kamel""""
        }

        test("anywhere in the criteria names the charset") {
            command { not { or { subject("a"); header("To", "Jürgen") } } }.toString() shouldBe
                    """UID SEARCH CHARSET UTF-8 NOT OR SUBJECT "a" HEADER "To" {7}Jürgen"""
        }
    }

    test("a line break is sent as a literal, without naming a charset") {
        command { subject("a\r\nb") }.toString() shouldBe "UID SEARCH SUBJECT {4}a\r\nb"
    }
})
