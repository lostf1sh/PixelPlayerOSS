package com.lostf1sh.pixelplayeross.utils

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import java.nio.charset.Charset
import java.text.Normalizer
import java.util.Locale

private val WINDOWS_1252: Charset = Charset.forName("windows-1252")

fun Color.toHexString(): String {
    return String.format(Locale.US, "#%08X", this.toArgb())
}

/**
 * Unicode replacement character (U+FFFD) — the universal "this byte could not be decoded"
 * marker.
 */
private const val REPLACEMENT_CHAR = '\uFFFD'

/**
 * The 27 "special" characters Windows-1252 assigns to bytes 0x80-0x9F (curly quotes,
 * dashes, the euro sign, OE/Z ligatures, etc.). When the trailing byte of a misdecoded
 * UTF-8 sequence lands in that range it surfaces as one of these glyphs, so they belong in
 * the mojibake "second character" set alongside the U+0080-U+00BF Latin-1 block.
 */
private const val WINDOWS_1252_SPECIALS =
    "€‚ƒ„…†‡ˆ‰Š‹ŒŽ" +
        "‘’“”•–—˜™š›œžŸ"

/**
 * Multi-character mojibake signature for UTF-8 text decoded as Windows-1252/ISO-8859-1. A
 * UTF-8 byte pair (lead 0xC2/0xC3/0xC5, then 0x80-0xBF) misdecoded this way renders as a
 * Latin-1 lead character ('Â', 'Ã' or 'Å') immediately followed by either a
 * U+0080-U+00BF character or one of the Windows-1252 special glyphs.
 *
 * The match always requires the lead char to be followed by a second mojibake character,
 * never a bare lead char, so legitimately-encoded UTF-8 text containing a standalone
 * accented letter (e.g. 'â' in "âme"/"château", an isolated 'Ã'/'Å') is NOT
 * flagged and therefore not re-encoded. Validated to flag none of a corpus of correctly
 * encoded accented titles while catching their Windows-1252-misread counterparts.
 */
private val MOJIBAKE_LEAD_REGEX =
    Regex("[ÂÃÅ][-¿$WINDOWS_1252_SPECIALS]")

/**
 * The smart-quote / em-dash family (UTF-8 0xE2 0x80 0xXX) misreads to 'â' followed by a
 * Windows-1252 special punctuation glyph, or — when the trailing bytes are decoded as
 * ISO-8859-1 rather than Windows-1252 — by a U+0080–U+00BF character. 'â' on its own is a
 * legitimate letter, so only these two-character pairs are treated as mojibake.
 */
private val MOJIBAKE_PUNCTUATION_REGEX =
    Regex("â[-¿$WINDOWS_1252_SPECIALS]")

private fun String.hasMojibakeSignature(): Boolean =
    MOJIBAKE_LEAD_REGEX.containsMatchIn(this) || MOJIBAKE_PUNCTUATION_REGEX.containsMatchIn(this)

/**
 * Counts U+FFFD replacement characters. Fewer replacement chars after a re-encode means
 * the candidate is more likely to be the intended text.
 */
private fun String.replacementCharCount(): Int = this.count { it == REPLACEMENT_CHAR }

/**
 * Attempts to fix incorrectly encoded metadata strings that frequently appear when
 * tags are saved using Windows-1252/ISO-8859-1 but are later read as UTF-8. This results
 * in characters such as "Ã©", "â€¦" or replacement symbols appearing instead of expected
 * punctuation. The function re-encodes the text when those multi-byte mojibake patterns
 * are detected and removes stray control characters while keeping the original text when
 * no adjustment is necessary.
 *
 * The detection deliberately requires a multi-character mojibake signature (or an explicit
 * U+FFFD replacement char) rather than a single bare accented character, so genuinely
 * UTF-8 metadata containing lone accented letters (French "âme"/"château", Portuguese,
 * Vietnamese, etc.) is left untouched. The re-encoded candidate is also only preferred
 * when it does not increase the number of replacement characters, so a misfire cannot
 * corrupt correctly-encoded text.
 */
fun String?.normalizeMetadataText(): String? {
    if (this == null) return null
    val trimmed = this.trim()
    if (trimmed.isEmpty()) return trimmed

    // Only treat the text as mis-encoded when it carries a multi-byte mojibake signature
    // (or an explicit U+FFFD replacement char), so genuine UTF-8 strings containing a lone
    // accented character such as 'â' ("âme"/"château") are left untouched.
    val needsFix = trimmed.contains(REPLACEMENT_CHAR) || trimmed.hasMojibakeSignature()

    val reencoded = if (needsFix) {
        runCatching {
            String(trimmed.toByteArray(WINDOWS_1252), Charsets.UTF_8).trim()
        }.getOrNull()
    } else null

    // Prefer the re-encoded candidate only when it is actually an improvement: non-empty
    // and not introducing more replacement characters than the original. Otherwise keep the
    // original text rather than risk corrupting legitimately-encoded metadata.
    val candidate = reencoded
        ?.takeIf { it.isNotEmpty() && it.replacementCharCount() <= trimmed.replacementCharCount() }
        ?: trimmed

    val cleaned = candidate.replace("\u0000", "")

    return Normalizer.normalize(cleaned, Normalizer.Form.NFC)
}

fun String?.normalizeMetadataTextOrEmpty(): String {
    return normalizeMetadataText() ?: ""
}

/**
 * Escape sequence for delimiters in artist names.
 * Use double backslash (\\) before a delimiter to prevent splitting at that position.
 * Example: "AC\\\\DC" with delimiter "/" won't split, but "Artist1/Artist2" will.
 */
private const val ESCAPE_SEQUENCE = "\\\\"

/**
 * Placeholder used internally during parsing to preserve escaped delimiters.
 */
private const val ESCAPE_PLACEHOLDER = "\u0000ESCAPED\u0000"

/**
 * Default word-based delimiters for splitting multi-artist strings.
 * These are matched case-insensitively with word boundaries.
 */
val DEFAULT_WORD_DELIMITERS = listOf("featuring", "feat.", "feat", "ft.", "ft", "vs.", "vs", "versus", "with", "prod.", "prod")

/**
 * Splits an artist string by the given character delimiters and word delimiters,
 * respecting escaped delimiters.
 *
 * @param delimiters List of character delimiter strings to split by (e.g., ["/", ";", ","])
 * @param wordDelimiters List of word-based delimiters to split by (e.g., ["feat.", "ft.", "vs."])
 *        These are matched case-insensitively with surrounding whitespace.
 *        The single-letter "x" is handled specially — only matched when surrounded by spaces.
 * @return List of individual artist names, trimmed and with escaped delimiters restored.
 *         Returns a single-element list with the original string if no splitting occurs.
 *
 * Examples:
 * - "Artist1/Artist2".splitArtistsByDelimiters(listOf("/")) -> ["Artist1", "Artist2"]
 * - "AC\\DC".splitArtistsByDelimiters(listOf("/")) -> ["AC/DC"] (escaped)
 * - "Drake feat. Rihanna".splitArtistsByDelimiters(listOf(), listOf("feat.")) -> ["Drake", "Rihanna"]
 * - "Marshmello x Bastille".splitArtistsByDelimiters(listOf(), listOf("x")) -> ["Marshmello", "Bastille"]
 */
fun String.splitArtistsByDelimiters(
    delimiters: List<String>,
    wordDelimiters: List<String> = DEFAULT_WORD_DELIMITERS
): List<String> {
    if ((delimiters.isEmpty() && wordDelimiters.isEmpty()) || this.isBlank()) {
        return listOf(this.trim()).filter { it.isNotEmpty() }
    }

    // Sort delimiters by length descending to handle longer delimiters first
    val sortedDelimiters = delimiters.sortedByDescending { it.length }

    var working = this

    // Replace escaped delimiters with placeholders
    val escapedMappings = mutableMapOf<String, String>()
    sortedDelimiters.forEachIndexed { index, delimiter ->
        val escapedDelimiter = ESCAPE_SEQUENCE + delimiter
        val placeholder = "${ESCAPE_PLACEHOLDER}${index}${ESCAPE_PLACEHOLDER}"
        escapedMappings[placeholder] = delimiter
        working = working.replace(escapedDelimiter, placeholder)
    }

    // Build combined regex pattern:
    // 1. Word delimiters: matched case-insensitively with whitespace boundaries
    //    Single-char word delimiters (like "x") require spaces on both sides
    // 2. Character delimiters: matched literally (with optional surrounding whitespace)
    val patternParts = mutableListOf<String>()

    // Word delimiters first (longer ones first to avoid partial matches)
    val sortedWordDelimiters = wordDelimiters.sortedByDescending { it.length }
    for (wd in sortedWordDelimiters) {
        val escaped = Regex.escape(wd)
        if (wd.length == 1) {
            // Single-char word delimiters (e.g., "x") — require spaces on both sides
            patternParts.add("\\s+$escaped\\s+")
        } else {
            // Multi-char word delimiters — require word boundary or whitespace
            patternParts.add("\\s+$escaped\\s+|\\s+$escaped$|^$escaped\\s+")
        }
    }

    // Character delimiters
    if (sortedDelimiters.isNotEmpty()) {
        val charPattern = sortedDelimiters.joinToString("|") { Regex.escape(it) }
        patternParts.add(charPattern)
    }

    if (patternParts.isEmpty()) {
        return listOf(this.trim()).filter { it.isNotEmpty() }
    }

    val combinedPattern = patternParts.joinToString("|")
    val regex = Regex(combinedPattern, RegexOption.IGNORE_CASE)

    // Split by combined pattern
    val parts = working.split(regex)

    // Restore escaped delimiters and trim each part
    return parts
        .map { part ->
            var restored = part
            escapedMappings.forEach { (placeholder, delimiter) ->
                restored = restored.replace(placeholder, delimiter)
            }
            restored.trim()
        }
        .filter { it.isNotEmpty() }
        .distinct()
        .ifEmpty { if (this.trim().isNotEmpty()) listOf(this.trim()) else emptyList() }
}

/**
 * Extracts featured artists from a song title and returns the cleaned title + extracted artists.
 *
 * Detects patterns like:
 * - "Song (feat. Artist)" / "Song [feat. Artist]"
 * - "Song (ft. Artist1 & Artist2)" / "Song [with Artist]"
 *
 * @param delimiters Character delimiters to further split extracted artist strings
 * @param wordDelimiters Word delimiters to further split extracted artist strings
 * @return Pair of (cleaned title, list of extracted artist names). Empty list if no artists found.
 */
fun String.extractArtistsFromTitle(
    delimiters: List<String> = emptyList(),
    wordDelimiters: List<String> = DEFAULT_WORD_DELIMITERS
): Pair<String, List<String>> {
    if (this.isBlank()) return this to emptyList()

    // Match patterns like (feat. ...), [ft. ...], (with ...), etc.
    val featureKeywords = listOf("featuring", "feat\\.", "feat", "ft\\.", "ft", "with", "prod\\.", "prod")
    val keywordPattern = featureKeywords.joinToString("|")
    val bracketPattern = Regex(
        """[\(\[]\\?\s*(?:$keywordPattern)\s+(.+?)\s*[\)\]]""",
        RegexOption.IGNORE_CASE
    )

    val extractedArtists = mutableListOf<String>()
    var cleanedTitle = this

    bracketPattern.findAll(this).forEach { match ->
        val artistString = match.groupValues[1]
        // Split the extracted artist string by delimiters (handles "Artist1 & Artist2" inside parens)
        val artists = artistString.splitArtistsByDelimiters(delimiters, wordDelimiters)
        extractedArtists.addAll(artists)
        cleanedTitle = cleanedTitle.replace(match.value, "")
    }

    return cleanedTitle.trim() to extractedArtists.distinct()
}

/**
 * Joins a list of artist names into a display string.
 *
 * @param separator The separator to use between artist names (default: ", ")
 * @return A formatted string with all artist names joined.
 */
fun List<String>.joinArtistsForDisplay(separator: String = ", "): String {
    return this.joinToString(separator)
}
