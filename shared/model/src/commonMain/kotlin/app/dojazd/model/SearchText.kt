package app.dojazd.model

object SearchText {
    private val folded =
        mapOf(
            'ą' to 'a',
            'ć' to 'c',
            'ę' to 'e',
            'ł' to 'l',
            'ń' to 'n',
            'ó' to 'o',
            'ś' to 's',
            'ź' to 'z',
            'ż' to 'z',
        )

    /**
     * Lowercases and strips Polish diacritics so that "lekno" finds "Łękno".
     * Other alphabets pass through unchanged; extend the table as cities are added.
     */
    fun fold(text: String): String =
        buildString(text.length) {
            for (char in text.lowercase()) append(folded[char] ?: char)
        }
}
