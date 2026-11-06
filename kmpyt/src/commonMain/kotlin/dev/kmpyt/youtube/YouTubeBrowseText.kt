package dev.kmpyt.youtube

/** Resource bounds reject complete inputs; they never silently shorten a search or media title. */
object YouTubeBrowseText {
    /** UTF-16 code units, with well-formed surrogate pairs required on every platform. */
    const val MAX_TITLE_LENGTH: Int = 16_384

    fun isCompleteTitle(value: String): Boolean {
        if (value.length !in 1..MAX_TITLE_LENGTH || value.isBlank()) return false
        var index = 0
        while (index < value.length) {
            val char = value[index++]
            if (char.code < 32 || char.code in 127..159) return false
            if (char.isHighSurrogate()) {
                if (index == value.length || !value[index++].isLowSurrogate()) return false
            } else if (char.isLowSurrogate()) return false
        }
        return true
    }
}
