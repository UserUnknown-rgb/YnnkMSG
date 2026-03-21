package com.Ynnk.YnnkMsg.util

import android.content.Context
import com.Ynnk.YnnkMsg.R

/**
 * Base26 encoder/decoder, ported from Python reference implementation.
 * Encodes bytes as a-z characters with noise separators (spaces, punctuation).
 */
object Base26Encoder {

    private val ALPHA = "abcdefghijklmnopqrstuvwxyz"
    private val ALPHA_SET = ALPHA.toSet()
    private const val BASE = 26
    private val digitTemplates: List<String> = listOf(
        "(dd.mm)",
        "(dd.mm.yyyy)",
        "(dd-mm-yyyy)",
        "(dd.mm, dd.mm)",
        "(dd.mm-dd.mm)",
        "(dd.mm.yyyy-dd.mm.yyyy)",
        "(dd.mm yyyy)",
        "(dd-mm)",
        "(yyyy)",
        "(-?)",
        "(-??)",
        "(-???)",
        "(??)",
        "(?.?)",
        "(#???)",
        "(№ ???)",
        "(№ ???/??)",
        "(#????)",
        "(%%-????)",
        "(*-????)",
        "(*/*-?????)",
        "(??-????)",
        "(??-??-yyyy)",
        "(dd/ mm/ yyyy)",
        "(dd.mm.yy)",
        "(dd-mm-yy)",
        "(??.??)",
        "(??-??)",
        "(??/??)",
        "(dd.mm hh:nn)",
        "(dd-mm hh:nn)",
        "(??:?? dd.mm)",
        "(??:?? dd-mm)"
    )

    private fun intToChars(value: Int, width: Int): String {
        val result = CharArray(width)
        var v = value
        for (i in width - 1 downTo 0) {
            result[i] = ALPHA[v % BASE]
            v /= BASE
        }
        return String(result)
    }

    private fun charsToInt(s: String): Int {
        var v = 0
        for (c in s.lowercase()) {
            v = v * BASE + ALPHA.indexOf(c)
        }
        return v
    }

    private fun digitSeparator(): String {
        val prefix = " " //if ((0..8).random() > 6) " " else ""
        val suffix = if ((0..15).random() > 12) ". " else " "
        return (prefix + fillDigitTemplate(digitTemplates.random()) + suffix)
    }

    private fun fillDigitTemplate(template: String): String {
        var result = template;
        result = result.replace("dd", (1..31).random().toString().padStart(2, '0'))
        result = result.replace("mm", (1..12).random().toString().padStart(2, '0'))
        result = result.replace("yyyy", (1990..2030).random().toString())
        result = result.replace("yy", (1..31).random().toString().padStart(2, '0'))
        result = result.replace("hh", (0..59).random().toString().padStart(2, '0'))
        result = result.replace("nn", (0..59).random().toString().padStart(2, '0'))

        result = result.replace("????????", (10000000..99999999).random().toString())
        result = result.replace("???????", (1000000..9999999).random().toString())
        result = result.replace("??????", (100000..999999).random().toString())
        result = result.replace("?????", (10000..99999).random().toString())
        result = result.replace("????", (1000..9999).random().toString())
        result = result.replace("???", (100..999).random().toString())
        result = result.replace("??", (10..99).random().toString())
        result = result.replace("?", (0..9).random().toString())

        return result
    }

    fun encode(data: ByteArray, allowDigits: Boolean = true): String {
        // Build list of information characters (lowercase)
        val info = mutableListOf<Char>()
        intToChars(data.size, 3).forEach { info.add(it) }
        for (byte in data) {
            intToChars(byte.toInt() and 0xFF, 2).forEach { info.add(it) }
        }

        val result = StringBuilder()
        var sinceComma = 999
        var sinceSent = 999
        var sinceExcl = 999
        var sinceCrLf = 999;
        var sinceNumbers = 999;
        var capitalizeNext = true
        var chunkLimit = (4..9).random()
        var chunkCount = 0

        for (idx in info.indices) {
            val ch = info[idx]
            result.append(if (capitalizeNext) ch.uppercaseChar() else ch)
            capitalizeNext = false
            sinceComma++
            sinceSent++
            sinceExcl++
            chunkCount++
            sinceCrLf++
            sinceNumbers++

            if (idx < info.size - 1 && chunkCount >= chunkLimit) {
                val bUseDigitalSeparator = allowDigits && (sinceNumbers > 250 && (0..9).random() > 8)
                if (bUseDigitalSeparator)
                {
                    sinceNumbers = 0;
                    sinceSent = 0
                }
                val sep = if (bUseDigitalSeparator) digitSeparator() else chooseSeparator(sinceComma, sinceSent, sinceExcl, sinceCrLf)
                result.append(sep)
                when {
                    sep == ", " -> sinceComma = 0
                    sep.endsWith(" ") && sep[0] in ".!?" -> {
                        sinceSent = 0
                        capitalizeNext = true
                        if (sep[0] in "!?") sinceExcl = 0
                    }
                    sep.startsWith("\r\n") -> {
                        sinceCrLf = 0
                        sinceSent = 0
                        capitalizeNext = true
                    }
                    sep.endsWith(". ") -> {
                        capitalizeNext = true
                    }
                }
                chunkCount = 0
                chunkLimit = (4..9).random()
            }
        }
        result.append('.')
        return result.toString()
    }

    private fun chooseSeparator(sinceComma: Int, sinceSent: Int, sinceExcl: Int, sinceCrLf: Int): String {
        val options = mutableListOf(" ", " ", " ")
        if (sinceComma >= 30) options.addAll(listOf(", ", ", "))
        if (sinceSent >= 50) {
            options.addAll(listOf(". ", ". ", ". "))
            if (sinceExcl >= 150) options.addAll(listOf("! ", "? "))
        }
        if (sinceCrLf >= 450) {
            options.addAll(listOf("\r\n", "\r\n\r\n"))
        }
        return options.random()
    }

    fun decode(text: String, context: Context): ByteArray {
        val chars = text.lowercase().filter { it in ALPHA_SET }
        if (chars.length < 3) throw IllegalArgumentException(context.getString(R.string.error_string_too_short))

        val length = charsToInt(chars.substring(0, 3))
        val dataChars = chars.substring(3)

        val expected = length * 2
        if (dataChars.length < expected) {
            throw IllegalArgumentException(context.getString(R.string.error_expected_chars, expected, dataChars.length))
        }

        return ByteArray(length) { i ->
            charsToInt(dataChars.substring(i * 2, i * 2 + 2)).toByte()
        }
    }

    fun encodeText(text: String, allowDigits: Boolean = true): String {
        return encode(text.toByteArray(Charsets.UTF_8), allowDigits)
    }

    fun decodeText(encoded: String, context: Context): String {
        return decode(encoded, context).toString(Charsets.UTF_8)
    }
}
