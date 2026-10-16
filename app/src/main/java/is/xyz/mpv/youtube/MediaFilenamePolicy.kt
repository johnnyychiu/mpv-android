package `is`.xyz.mpv.youtube

import java.util.Locale

/** Portable display names for SAF documents; temporary download paths remain video IDs. */
internal object MediaFilenamePolicy {
    const val MAX_UTF8_BYTES = 240
    private const val FORBIDDEN = "<>:\"/\\|?*"
    private val extensionPattern = Regex("[a-z0-9]{1,16}")
    private val reservedNames = setOf("CON", "PRN", "AUX", "NUL", "CLOCK$", "CONIN$", "CONOUT$") +
        (('0'..'9') + listOf('¹', '²', '³')).flatMap { listOf("COM$it", "LPT$it") }

    /** [extension] may have one leading dot; an empty extension preserves extensionless files. */
    fun filename(title: String, extension: String, fallback: String = "video"): String {
        val normalizedExtension = extension.removePrefix(".").lowercase(Locale.ROOT)
        require(normalizedExtension.isEmpty() || extensionPattern.matches(normalizedExtension)) {
            "Use a valid media file extension."
        }
        val suffix = if (normalizedExtension.isEmpty()) "" else ".$normalizedExtension"
        val budget = MAX_UTF8_BYTES - suffix.length
        val candidate = cleanBase(title, suffix).ifEmpty { cleanBase(fallback, suffix) }.ifEmpty { "video" }
        var base = cleanBase(takeUtf8(candidate, budget), suffix).ifEmpty { "video" }
        // Windows reserves these names even when another extension follows them.
        if (base.substringBefore('.').uppercase(Locale.ROOT) in reservedNames) {
            base = "_" + takeUtf8(base, budget - 1)
        }
        return base + suffix
    }

    private fun cleanBase(value: String, suffix: String): String {
        val cleaned = StringBuilder()
        var index = 0
        while (index < value.length) {
            val codePoint = Character.codePointAt(value, index)
            index += Character.charCount(codePoint)
            if (Character.isISOControl(codePoint) || codePoint in 0xD800..0xDFFF ||
                codePoint in 0x202A..0x202E || codePoint in 0x2066..0x2069 ||
                (codePoint < 128 && codePoint.toChar() in FORBIDDEN)) continue
            cleaned.appendCodePoint(codePoint)
        }
        var base = cleaned.toString().trim { it.isWhitespace() }.trimEnd('.')
        while (suffix.isNotEmpty() && base.endsWith(suffix, ignoreCase = true)) {
            base = base.dropLast(suffix.length).trimEnd { it.isWhitespace() || it == '.' }
        }
        return base.trim { it.isWhitespace() || it == '.' }
    }

    private fun takeUtf8(value: String, maxBytes: Int): String {
        var index = 0
        var bytes = 0
        while (index < value.length) {
            val codePoint = Character.codePointAt(value, index)
            val size = when {
                codePoint <= 0x7F -> 1
                codePoint <= 0x7FF -> 2
                codePoint <= 0xFFFF -> 3
                else -> 4
            }
            if (bytes + size > maxBytes) break
            bytes += size
            index += Character.charCount(codePoint)
        }
        return value.substring(0, index)
    }
}
