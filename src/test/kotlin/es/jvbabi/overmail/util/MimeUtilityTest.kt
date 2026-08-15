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

        test("text that only looks like an encoded word is returned unchanged") {
            MimeUtility.decode("=?SHIFT_JIS?B?SGFsbG8=?=") shouldBe "=?SHIFT_JIS?B?SGFsbG8=?="
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

        test("known limitation: iso-8859-15 is decoded as iso-8859-1, so 0xA4 becomes ¤ instead of €") {
            MimeUtility.decode("=?ISO-8859-15?Q?=A4?=") shouldBe "¤"
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
