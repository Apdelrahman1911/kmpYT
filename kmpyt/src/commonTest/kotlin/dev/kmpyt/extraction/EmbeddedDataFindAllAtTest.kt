package dev.kmpyt.extraction

import kotlin.test.Test
import kotlin.test.assertEquals

/** findAllAt must return exactly what Regex.findAll returns when matches start with a prefix. */
class EmbeddedDataFindAllAtTest {
    private fun same(regex: Regex, text: String, prefixes: List<String>) =
        assertEquals(regex.findAll(text).map { it.range to it.value }.toList(),
            EmbeddedData.findAllAt(regex, text, prefixes).map { it.range to it.value }.toList(), text)

    @Test fun assignmentsMatchTheRegexIncludingNestedAndCaseVariants() {
        val regex = Regex("(?:ytInitialPlayerResponse|playerResponse)\\s*[=:]\\s*", RegexOption.IGNORE_CASE)
        val prefixes = listOf("ytInitialPlayerResponse", "playerResponse")
        for (text in listOf(
            "var ytInitialPlayerResponse = {\"a\":1};",
            "x ytInitialPlayerResponse;y \"playerResponse\":{} PLAYERRESPONSE : {}",
            "ytinitialplayerresponse=1 ytInitialPlayerResponsePlayerResponse = 2",
            "no assignment here playerResponse",
            "",
        )) same(regex, text, prefixes)
    }

    @Test fun metaAndScriptTagsMatchTheRegex() {
        val meta = Regex("<(?:meta|link)\\b[^>]{0,16000}>", RegexOption.IGNORE_CASE)
        val script = Regex("<script\\b[^>]*type\\s*=\\s*[\"']application/ld\\+json[\"'][^>]*>([\\s\\S]*?)</script\\s*>", RegexOption.IGNORE_CASE)
        val html = "<META name=\"title\" content=\"T\"><metadata><link rel=\"canonical\" href=\"u\"><Script type='application/ld+json'>{}</script >" +
            "<script>x</script><script type=\"application/ld+json\">{\"b\":2}</SCRIPT><meta"
        same(meta, html, listOf("<meta", "<link"))
        same(script, html, listOf("<script"))
        for (page in listOf(html, "<script type=\"application/ld+json\">{}", "<SCRIPT TYPE='application/ld+json' x>a</script\n >b</script>",
            "<script type=\"text/javascript\">{}</script><script type=\"application/ld+json\">{\"c\":3}</script>")) {
            assertEquals(script.findAll(page).map { it.groupValues[1] }.toList(), EmbeddedData.ldJsonScripts(page).toList(), page)
        }
    }
}
