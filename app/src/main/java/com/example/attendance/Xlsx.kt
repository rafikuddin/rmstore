package com.example.attendance

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.math.BigDecimal
import java.util.zip.ZipInputStream

/**
 * A small .xlsx reader (only the JDK is used): returns the first sheet as rows of text.
 * An .xlsx file is a zip of XML files; the text of the cells is either inside the sheet or in "sharedStrings".
 * Numbers come back as plain text without a trailing ".0", so 2816 stays "2816".
 */
object Xlsx {
    private const val MAX_ENTRY_BYTES = 60_000_000

    fun read(input: InputStream): List<List<String>> {
        var shared: String? = null
        var workbook: String? = null
        var rels: String? = null
        val sheets = HashMap<String, String>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val name = e.name
                val wanted = name == "xl/sharedStrings.xml" || name == "xl/workbook.xml" || name == "xl/_rels/workbook.xml.rels" ||
                    (name.startsWith("xl/worksheets/") && name.endsWith(".xml") && !name.contains("/_rels/"))
                if (!wanted) continue
                val buf = ByteArrayOutputStream()
                val chunk = ByteArray(16384)
                var total = 0
                while (true) {
                    val n = zip.read(chunk)
                    if (n < 0) break
                    total += n
                    if (total > MAX_ENTRY_BYTES) throw IllegalArgumentException("This file is too big to read on the phone.")
                    buf.write(chunk, 0, n)
                }
                val text = buf.toString("UTF-8")
                when (name) {
                    "xl/sharedStrings.xml" -> shared = text
                    "xl/workbook.xml" -> workbook = text
                    "xl/_rels/workbook.xml.rels" -> rels = text
                    else -> sheets[name] = text
                }
            }
        }
        if (sheets.isEmpty()) throw IllegalArgumentException("This is not an Excel (.xlsx) file, or it has no sheet.")
        val sheetXml = sheets[firstSheetPath(workbook, rels, sheets.keys)] ?: sheets[sheets.keys.sorted().first()]!!
        return parseSheet(sheetXml, parseShared(shared))
    }

    /** The path of the first sheet in tab order, or the lowest-numbered sheet file when the workbook cannot be read. */
    private fun firstSheetPath(workbook: String?, rels: String?, have: Set<String>): String {
        try {
            if (workbook != null && rels != null) {
                val rid = Regex("<sheet\\b[^>]*?\\br:id=\"([^\"]+)\"").find(workbook)?.groupValues?.get(1)
                if (rid != null) {
                    for (m in Regex("<Relationship\\b([^>]*?)/?>").findAll(rels)) {
                        val a = m.groupValues[1]
                        if (attr(a, "Id") == rid) {
                            var t = attr(a, "Target") ?: break
                            t = if (t.startsWith("/")) t.substring(1) else "xl/$t"
                            if (have.contains(t)) return t
                        }
                    }
                }
            }
        } catch (_: Exception) { }
        return have.sorted().first()
    }

    private fun attr(attrs: String, name: String): String? =
        Regex("(?:^|\\s)" + Regex.escape(name) + "=\"([^\"]*)\"").find(attrs)?.groupValues?.get(1)

    private fun parseShared(xml: String?): List<String> {
        if (xml == null) return emptyList()
        val out = ArrayList<String>()
        for (m in Regex("<si\\b[^>]*?(?:/>|>(.*?)</si>)", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
            val body = (m.groupValues[1]).replace(Regex("<rPh\\b.*?</rPh>", RegexOption.DOT_MATCHES_ALL), "")
            val sb = StringBuilder()
            for (t in Regex("<t\\b[^>]*?(?:/>|>(.*?)</t>)", RegexOption.DOT_MATCHES_ALL).findAll(body)) sb.append(t.groupValues[1])
            out.add(unescape(sb.toString()))
        }
        return out
    }

    private fun parseSheet(xml: String, shared: List<String>): List<List<String>> {
        val cells = HashMap<Long, String>()
        var maxRow = 0
        var maxCol = 0
        for (m in Regex("<c\\b([^>]*?)(?:/>|>(.*?)</c>)", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
            val a = m.groupValues[1]
            val inner = m.groupValues[2]
            if (inner.isEmpty()) continue
            val ref = attr(a, "r") ?: continue
            val letters = ref.takeWhile { it.isLetter() }
            val row = ref.drop(letters.length).toIntOrNull() ?: continue
            val col = colIndex(letters)
            if (col < 0 || row < 1) continue
            val type = attr(a, "t") ?: "n"
            val raw = Regex("<v>(.*?)</v>", RegexOption.DOT_MATCHES_ALL).find(inner)?.groupValues?.get(1)
            val value: String = when (type) {
                "s" -> raw?.trim()?.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                "inlineStr" -> unescape(Regex("<t\\b[^>]*?>(.*?)</t>", RegexOption.DOT_MATCHES_ALL).findAll(inner).joinToString("") { it.groupValues[1] })
                "str", "d" -> unescape(raw ?: "")
                "b" -> if (raw?.trim() == "1") "TRUE" else "FALSE"
                "e" -> ""
                else -> number(raw ?: "")
            }
            if (value.isEmpty()) continue
            cells[row.toLong() * 100000L + col] = value
            if (row > maxRow) maxRow = row
            if (col + 1 > maxCol) maxCol = col + 1
        }
        val rows = ArrayList<List<String>>(maxRow)
        for (r in 1..maxRow) rows.add(List(maxCol) { c -> cells[r.toLong() * 100000L + c] ?: "" })
        return rows
    }

    private fun colIndex(letters: String): Int {
        if (letters.isEmpty()) return -1
        var n = 0
        for (ch in letters.uppercase()) { if (ch !in 'A'..'Z') return -1; n = n * 26 + (ch - 'A' + 1) }
        return n - 1
    }

    private fun number(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return ""
        return try {
            val d = BigDecimal(s)
            if (d.scale() <= 0 || d.stripTrailingZeros().scale() <= 0) d.toBigInteger().toString() else d.stripTrailingZeros().toPlainString()
        } catch (_: Exception) { s }
    }

    private fun unescape(s: String): String {
        if (s.indexOf('&') < 0) return s
        return Regex("&(#x[0-9A-Fa-f]+|#[0-9]+|amp|lt|gt|quot|apos);").replace(s) { m ->
            when (val g = m.groupValues[1]) {
                "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"
                else -> try {
                    val code = if (g.startsWith("#x")) g.substring(2).toInt(16) else g.substring(1).toInt()
                    String(Character.toChars(code))
                } catch (_: Exception) { m.value }
            }
        }
    }
}
