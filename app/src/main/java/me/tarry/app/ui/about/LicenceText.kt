package me.tarry.app.ui.about

private val ListLine = Regex("""^(\s|[*-]|\d+\.).*""")

/**
 * Licence files are hard-wrapped at about 78 columns, which looks ragged on a phone. Joins the single newlines inside a
 * paragraph into spaces. Blank-line paragraph breaks stay, and so do list and indented lines (they start with whitespace,
 * "*", "-" or a number and a full stop).
 */
fun reflowLicenceText(text: String): String {
    val out = StringBuilder()
    var previousJoinable = false
    var first = true
    for (line in text.replace("\r\n", "\n").split('\n')) {
        val joinable = line.isNotBlank() && !ListLine.matches(line)
        when {
            first -> out.append(line)
            joinable && previousJoinable -> out.append(' ').append(line.trim())
            else -> out.append('\n').append(line)
        }
        first = false
        previousJoinable = joinable
    }
    return out.toString()
}
