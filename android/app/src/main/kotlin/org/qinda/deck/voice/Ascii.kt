package org.qinda.deck.voice

import java.text.Normalizer

/**
 * Fold recognized speech into the byte set the deck can type.
 *
 * The firmware's text step walks a US ASCII table and silently skips anything
 * outside it, so a transcript full of curly quotes and accented vowels would
 * arrive with holes in it. Folding here — decompose accents, swap typographic
 * punctuation for its plain twin — means what you said is what gets typed.
 */
object Ascii {

    private val replacements = mapOf(
        '‘' to "'", '’' to "'", '‚' to "'", '‛' to "'",
        '“' to "\"", '”' to "\"", '„' to "\"", '‟' to "\"",
        '–' to "-", '—' to "-", '―' to "-", '−' to "-",
        '…' to "...",
        '•' to "*", '·' to "*",
        '«' to "\"", '»' to "\"",
        '‹' to "'", '›' to "'",
        ' ' to " ", ' ' to " ", '　' to " ",
        '×' to "x", '÷' to "/",
        '°' to " degrees ",
        '€' to "EUR", '£' to "GBP", '¥' to "JPY",
    )

    /** Fold to printable ASCII plus newline and tab; anything unmappable drops out. */
    fun fold(input: String): String {
        val decomposed = Normalizer.normalize(input, Normalizer.Form.NFKD)
        val out = StringBuilder(decomposed.length)
        for (c in decomposed) {
            when {
                c.code in 32..126 || c == '\n' || c == '\t' -> out.append(c)
                replacements.containsKey(c) -> out.append(replacements[c])
                // Combining marks left over from NFKD (accents) simply vanish,
                // which is the point: "é" became "e" + a mark just now.
                Character.getType(c) == Character.NON_SPACING_MARK.toInt() -> Unit
            }
        }
        return out.toString()
    }

    /**
     * Split for the firmware's 512-byte text-step ceiling, preferring word
     * boundaries. Pure ASCII by this point, so chars are bytes.
     */
    fun chunks(text: String, max: Int = 400): List<String> {
        if (text.length <= max) return listOf(text)
        val out = ArrayList<String>(text.length / max + 1)
        var rest = text
        while (rest.length > max) {
            var cut = rest.lastIndexOf(' ', max)
            if (cut < max / 2) cut = max
            out += rest.substring(0, cut)
            rest = rest.substring(cut).trimStart()
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }
}
