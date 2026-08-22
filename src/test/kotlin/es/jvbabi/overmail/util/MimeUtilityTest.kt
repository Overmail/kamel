package es.jvbabi.overmail.util

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MimeUtilityTest : FunSpec({

    context("decode - unencoded input") {
        test("plain ascii is returned unchanged") {
            MimeUtility.decode("Hello World") shouldBe "Hello World"
        }

        test("empty input is returned unchanged") {
            MimeUtility.decode("") shouldBe ""
        }

        test("an encoded word with an unknown charset is returned unchanged") {
            MimeUtility.decode("=?NOT-A-CHARSET?B?SGFsbG8=?=") shouldBe "=?NOT-A-CHARSET?B?SGFsbG8=?="
        }

        test("an encoded word with an illegal charset name is returned unchanged") {
            MimeUtility.decode("=?UTF-8@?B?SGFsbG8=?=") shouldBe "=?UTF-8@?B?SGFsbG8=?="
        }

        test("an encoded word with an undecodable body is returned unchanged") {
            MimeUtility.decode("=?UTF-8?B?SGFs!!8=?=") shouldBe "=?UTF-8?B?SGFs!!8=?="
        }
    }

    context("decode - base64 encoded words") {
        test("utf-8 base64") {
            MimeUtility.decode("=?UTF-8?B?SGFsbG8=?=") shouldBe "Hallo"
        }

        test("utf-8 base64 with non-ascii characters") {
            MimeUtility.decode("=?UTF-8?B?R3LDvMOfZQ==?=") shouldBe "Grüße"
        }

        test("charset and encoding are matched case insensitively") {
            MimeUtility.decode("=?utf-8?b?SGFsbG8=?=") shouldBe "Hallo"
        }

        test("missing base64 padding is tolerated") {
            MimeUtility.decode("=?UTF-8?B?SGFsbG8?=") shouldBe "Hallo"
        }

        test("shift_jis base64") {
            MimeUtility.decode("=?SHIFT_JIS?B?k/qWe4zq?=") shouldBe "日本語"
        }
    }

    context("decode - quoted printable encoded words") {
        test("utf-8 quoted printable") {
            MimeUtility.decode("=?UTF-8?Q?Gr=C3=BC=C3=9Fe?=") shouldBe "Grüße"
        }

        test("underscore is decoded as space") {
            MimeUtility.decode("=?UTF-8?Q?Hallo_Welt?=") shouldBe "Hallo Welt"
        }

        test("iso-8859-1 quoted printable") {
            MimeUtility.decode("=?ISO-8859-1?Q?Gr=FC=DFe?=") shouldBe "Grüße"
        }

        test("iso-8859-15 quoted printable") {
            MimeUtility.decode("=?ISO-8859-15?Q?Gr=FC=DFe?=") shouldBe "Grüße"
        }

        test("iso-8859-15 is decoded with its own table, so 0xA4 becomes €") {
            MimeUtility.decode("=?ISO-8859-15?Q?=A4?=") shouldBe "€"
        }

        test("windows-1252 quoted printable") {
            MimeUtility.decode("=?Windows-1252?Q?Gr=FC=DFe_=96_=80?=") shouldBe "Grüße – €"
        }

        test("an incomplete escape inside an encoded word is dropped") {
            MimeUtility.decode("=?UTF-8?Q?Hallo=?=") shouldBe "Hallo"
        }
    }

    context("decode - multiple words") {
        test("adjacent encoded words are concatenated without the separating whitespace") {
            MimeUtility.decode("=?UTF-8?B?SGFsbG8h?= =?UTF-8?B?V2ll?=") shouldBe "Hallo!Wie"
        }

        test("adjacent encoded words with base64 padding") {
            MimeUtility.decode("=?UTF-8?B?SGFsbG8=?= =?UTF-8?B?V2VsdA==?=") shouldBe "HalloWelt"
        }

        test("three adjacent encoded words") {
            MimeUtility.decode("=?UTF-8?B?SGFsbG8=?= =?UTF-8?B?V2VsdA==?= =?UTF-8?B?VGVzdA==?=") shouldBe "HalloWeltTest"
        }

        test("encoded word mixed with plain words keeps the whitespace") {
            MimeUtility.decode("Re: =?UTF-8?B?R3LDvMOfZQ==?= today") shouldBe "Re: Grüße today"
        }

        test("plain words are kept as they are") {
            MimeUtility.decode("Re: your invoice") shouldBe "Re: your invoice"
        }

        test("an encoded word next to an unknown one keeps the separating whitespace") {
            MimeUtility.decode("=?UTF-8?B?SGFsbG8=?= =?NOT-A-CHARSET?B?V2VsdA==?=") shouldBe
                    "Hallo =?NOT-A-CHARSET?B?V2VsdA==?="
        }
    }

    context("decode - folded headers") {
        test("a folded plain text value is unfolded") {
            MimeUtility.decode("Ein sehr langer\r\n Betreff") shouldBe "Ein sehr langer Betreff"
        }

        test("folding with a tab is unfolded to a single space") {
            MimeUtility.decode("Ein sehr langer\r\n\tBetreff") shouldBe "Ein sehr langer Betreff"
        }

        test("a bare line feed is treated as folding") {
            MimeUtility.decode("Ein sehr langer\n Betreff") shouldBe "Ein sehr langer Betreff"
        }

        test("a line break without folding whitespace still separates the words") {
            MimeUtility.decode("Ein sehr langer\r\nBetreff") shouldBe "Ein sehr langer Betreff"
        }

        test("a value starting on the next line is trimmed") {
            MimeUtility.decode("\r\n =?UTF-8?B?SGFsbG8=?=") shouldBe "Hallo"
        }

        test("folding between two encoded words is dropped") {
            MimeUtility.decode("=?UTF-8?B?SGFsbG8=?=\r\n =?UTF-8?B?V2VsdA==?=") shouldBe "HalloWelt"
        }

        test("folding between an encoded word and plain text keeps one space") {
            MimeUtility.decode("=?UTF-8?B?SGFsbG8=?=\r\n Welt") shouldBe "Hallo Welt"
        }

        test("surrounding whitespace is trimmed") {
            MimeUtility.decode("  Betreff  ") shouldBe "Betreff"
        }
    }

    context("decode - real world headers") {
        // Subject of a mail sent by Outlook: starts on the line after "Subject:", folded over two
        // lines, windows-1252 quoted printable.
        test("folded windows-1252 subject") {
            val subject = "\r\n =?Windows-1252?Q?Morgen_9:00_Uhr:_Start_der_HPI_Insight_Sessions_=96_Zoom?=" +
                    "\r\n =?Windows-1252?Q?-Link_&_Infos?="

            MimeUtility.decode(subject) shouldBe
                    "Morgen 9:00 Uhr: Start der HPI Insight Sessions – Zoom-Link & Infos"
        }
    }

    context("decodeQuotedPrintable") {
        test("hex escapes are decoded as utf-8") {
            MimeUtility.decodeQuotedPrintable("Gr=C3=BC=C3=9Fe") shouldBe "Grüße"
        }

        test("underscore is decoded as space") {
            MimeUtility.decodeQuotedPrintable("Hallo_Welt") shouldBe "Hallo Welt"
        }

        test("plain text is returned unchanged") {
            MimeUtility.decodeQuotedPrintable("Hallo") shouldBe "Hallo"
        }

        test("empty input") {
            MimeUtility.decodeQuotedPrintable("") shouldBe ""
        }

        test("an incomplete escape at the end drops the '=' and keeps the rest") {
            MimeUtility.decodeQuotedPrintable("Hallo=C") shouldBe "HalloC"
        }
    }
})
