package es.jvbabi.overmail.kamel

import es.jvbabi.overmail.kamel.SearchCriterion.AllOf
import es.jvbabi.overmail.kamel.SearchCriterion.AnyOf
import es.jvbabi.overmail.kamel.SearchCriterion.Header
import es.jvbabi.overmail.kamel.SearchCriterion.Not
import es.jvbabi.overmail.kamel.SearchCriterion.SentOn
import es.jvbabi.overmail.kamel.SearchCriterion.Subject
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

private fun criterion(criteria: SearchRequest.() -> Unit) = SearchRequest().apply(criteria).criterion

private fun message(vararg headers: String) =
    (headers.joinToString("\r\n") + "\r\n\r\nBody\r\n").toByteArray(Charsets.UTF_8)

private val arrived = Instant.parse("2025-05-05T12:00:00Z")

private fun SearchCriterion.matches(source: ByteArray) = matches(source, arrived)

class SearchRequestTest : FunSpec({

    context("the DSL") {
        test("without criteria builds an empty AND") {
            criterion { } shouldBe AllOf(emptyList())
        }

        test("with one criterion builds that criterion") {
            criterion { subject("Lunch") } shouldBe Subject("Lunch")
        }

        test("combines criteria next to each other with AND") {
            criterion {
                subject("Lunch")
                sentOn(LocalDate(2025, 5, 5))
            } shouldBe AllOf(listOf(Subject("Lunch"), SentOn(LocalDate(2025, 5, 5))))
        }

        test("nests or, and and not") {
            criterion {
                or {
                    and {
                        subject("a")
                        subject("b")
                    }
                    messageId("x@example.org")
                }
                not { subject("c") }
            } shouldBe AllOf(
                listOf(
                    AnyOf(listOf(AllOf(listOf(Subject("a"), Subject("b"))), Header("Message-ID", "x@example.org"))),
                    Not(Subject("c")),
                )
            )
        }

        test("negates several criteria of not together") {
            criterion {
                not {
                    subject("a")
                    subject("b")
                }
            } shouldBe Not(AllOf(listOf(Subject("a"), Subject("b"))))
        }

        test("takes criteria built elsewhere") {
            val spam = Header("X-Spam", "yes")
            criterion { add(spam) } shouldBe spam
        }

        test("refuses an empty or") {
            shouldThrow<IllegalArgumentException> { criterion { or { } } }
        }

        test("refuses an empty not") {
            shouldThrow<IllegalArgumentException> { criterion { not { } } }
        }

        test("refuses a header name that is none") {
            shouldThrow<IllegalArgumentException> { criterion { header("Message ID", "x") } }
            shouldThrow<IllegalArgumentException> { criterion { header("Subject:", "x") } }
            shouldThrow<IllegalArgumentException> { criterion { header("", "x") } }
        }

        test("refuses a string with NUL") {
            shouldThrow<IllegalArgumentException> { criterion { subject("a\u0000b") } }
        }
    }

    context("matches") {
        test("finds a subject by a part of it, ignoring case") {
            val mail = message("Subject: Lunch tomorrow?")

            criterion { subject("lunch TOMORROW") }.matches(mail) shouldBe true
            criterion { subject("dinner") }.matches(mail) shouldBe false
        }

        test("decodes an encoded subject before it compares") {
            val mail = message("Subject: =?UTF-8?Q?Gr=C3=BC=C3=9Fe_aus_M=C3=BCnchen?=")

            criterion { subject("grüße") }.matches(mail) shouldBe true
        }

        test("does not find a subject in a mail without one") {
            criterion { subject("") }.matches(message("From: ada@example.com")) shouldBe false
        }

        test("finds a header by its name in any case") {
            val mail = message("List-Id: Kotlin <kotlin.example.org>")

            criterion { header("list-id", "kotlin.example") }.matches(mail) shouldBe true
            criterion { header("List-Id", "java") }.matches(mail) shouldBe false
        }

        test("finds every mail with a header for an empty text") {
            criterion { header("X-Spam", "") }.matches(message("X-Spam: no")) shouldBe true
            criterion { header("X-Spam", "") }.matches(message("Subject: hi")) shouldBe false
        }

        test("finds a Message-ID with and without its angle brackets") {
            val mail = message("Message-ID: <x@example.org>")

            criterion { messageId("<x@example.org>") }.matches(mail) shouldBe true
            criterion { messageId("x@example.org") }.matches(mail) shouldBe true
            criterion { messageId("y@example.org") }.matches(mail) shouldBe false
        }

        context("the date it was sent") {
            // Late in the evening at -0500, already the next day in UTC.
            val mail = message("Date: Mon, 5 May 2025 23:30:00 -0500 (CDT)")

            test("is the day in the time zone of the Date header") {
                criterion { sentOn(LocalDate(2025, 5, 5)) }.matches(mail) shouldBe true
                criterion { sentOn(LocalDate(2025, 5, 6)) }.matches(mail) shouldBe false
            }

            test("is compared as a whole day by sentSince and sentBefore") {
                criterion { sentSince(LocalDate(2025, 5, 5)) }.matches(mail) shouldBe true
                criterion { sentSince(LocalDate(2025, 5, 6)) }.matches(mail) shouldBe false
                criterion { sentBefore(LocalDate(2025, 5, 6)) }.matches(mail) shouldBe true
                criterion { sentBefore(LocalDate(2025, 5, 5)) }.matches(mail) shouldBe false
            }

            test("is read without the day of the week and the seconds") {
                criterion { sentOn(LocalDate(2025, 5, 5)) }.matches(message("Date: 5 May 2025 14:03 +0200")) shouldBe true
            }

            test("is read with a zone name the formatter does not know") {
                criterion { sentOn(LocalDate(2025, 5, 5)) }.matches(message("Date: Mon, 5 May 2025 12:00:00 EST")) shouldBe true
            }

            test("is missing in a mail without a Date header") {
                val undated = message("Subject: hi")
                criterion { sentOn(LocalDate(2025, 5, 5)) }.matches(undated) shouldBe false
                criterion { sentSince(LocalDate(2000, 1, 1)) }.matches(undated) shouldBe false
                criterion { sentBefore(LocalDate(2100, 1, 1)) }.matches(undated) shouldBe false
            }
        }

        context("the date it arrived") {
            val mail = message("Subject: hi")
            val lateAtNight = Instant.parse("2025-05-05T23:30:00Z")

            test("is the day of receivedAt in the time zone of the server") {
                criterion { receivedOn(LocalDate(2025, 5, 5)) }.matches(mail, lateAtNight) shouldBe true
                criterion { receivedOn(LocalDate(2025, 5, 6)) }
                    .matches(mail, lateAtNight, TimeZone.of("Europe/Berlin")) shouldBe true
            }

            test("is compared as a whole day by receivedSince and receivedBefore") {
                criterion { receivedSince(LocalDate(2025, 5, 5)) }.matches(mail, lateAtNight) shouldBe true
                criterion { receivedSince(LocalDate(2025, 5, 6)) }.matches(mail, lateAtNight) shouldBe false
                criterion { receivedBefore(LocalDate(2025, 5, 6)) }.matches(mail, lateAtNight) shouldBe true
                criterion { receivedBefore(LocalDate(2025, 5, 5)) }.matches(mail, lateAtNight) shouldBe false
            }
        }

        test("needs every criterion of an AND") {
            val mail = message("Subject: Lunch", "Date: Mon, 5 May 2025 14:03:12 +0200")

            criterion { subject("Lunch"); sentOn(LocalDate(2025, 5, 5)) }.matches(mail) shouldBe true
            criterion { subject("Lunch"); sentOn(LocalDate(2025, 5, 6)) }.matches(mail) shouldBe false
        }

        test("needs one criterion of an OR") {
            val mail = message("Subject: Dinner")

            criterion { or { subject("Lunch"); subject("Dinner") } }.matches(mail) shouldBe true
            criterion { or { subject("Lunch"); subject("Breakfast") } }.matches(mail) shouldBe false
        }

        test("negates with NOT") {
            val mail = message("Subject: Dinner", "X-Spam: yes")

            criterion { not { subject("Lunch") } }.matches(mail) shouldBe true
            // Not both together: the subject does not match, so the mail does.
            criterion { not { subject("Lunch"); header("X-Spam", "yes") } }.matches(mail) shouldBe true
            criterion { not { or { subject("Lunch"); header("X-Spam", "yes") } } }.matches(mail) shouldBe false
        }

        test("finds every mail without criteria") {
            criterion { }.matches(message("Subject: hi")) shouldBe true
        }
    }
})
