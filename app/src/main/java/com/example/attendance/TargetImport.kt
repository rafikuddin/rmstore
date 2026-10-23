package com.example.attendance

/** Turns the rows of the two Excel files (Target, Sale Collection report) into what the server needs. Pure Kotlin. */
object TargetImport {
    class TargetRow(val username: String, val name: String, val baseCode: String, val baseName: String, val target: Double)
    class TargetResult(val rows: List<TargetRow>, val total: Double, val skipped: Int)

    class CollectionRow(
        val date: String, val username: String, val type: String, val amount: Double, val count: Int,
        val name: String = "", val baseCode: String = "", val baseName: String = "", val dealerCode: String = "", val dealerName: String = ""
    )
    class CollectionResult(
        val rows: List<CollectionRow>, val fileRows: Int, val from: String, val to: String,
        val total: Double, val unassigned: Double, val unassignedRows: Int, val people: Int, val skipped: Int, val types: Map<String, Double>,
        val dealers: Int = 0, val hasDealer: Boolean = false
    )

    private fun key(h: String) = h.lowercase().filter { it.isLetterOrDigit() }

    /** Finds the header row (first 15 rows) holding all [required] names; returns the row index and a name -> column map. */
    private fun header(table: List<List<String>>, required: List<List<String>>, what: String): Pair<Int, Map<String, Int>> {
        for (i in 0 until minOf(15, table.size)) {
            val keys = table[i].map { key(it) }
            val map = HashMap<String, Int>()
            for (names in required) {
                val c = names.map { keys.indexOf(it) }.firstOrNull { it >= 0 } ?: -1
                if (c >= 0) map[names[0]] = c
            }
            if (map.size == required.size) {
                for ((idx, k) in keys.withIndex()) if (k.isNotEmpty() && !map.containsValue(idx)) map.putIfAbsent("col:$k", idx)
                return Pair(i, map)
            }
        }
        throw IllegalArgumentException("This does not look like the $what file. The first row must have the columns: " +
            required.joinToString(", ") { it[0] } + ".")
    }

    private fun userOf(s: String): String {
        val t = s.trim()
        return if (Regex("^-?\\d+\\.0+$").matches(t)) t.substringBefore('.') else t
    }

    fun amount(s: String): Double? {
        var t = s.trim().replace(",", "").replace(" ", "")
        if (t.isEmpty()) return null
        var neg = false
        if (t.startsWith("(") && t.endsWith(")")) { neg = true; t = t.substring(1, t.length - 1) }
        val d = t.toDoubleOrNull() ?: return null
        return if (neg) -d else d
    }

    fun parseTarget(table: List<List<String>>): TargetResult {
        val (h, m) = header(table, listOf(listOf("username", "userid", "user", "employeeid", "id"), listOf("target", "monthlytarget", "targetamount")), "Target")
        val u = m["username"]!!; val t = m["target"]!!
        val name = m["col:name"]; val bc = m["col:basecode"]; val bn = m["col:basename"]
        val byUser = LinkedHashMap<String, TargetRow>()
        var skipped = 0
        for (i in h + 1 until table.size) {
            val r = table[i]
            if (r.all { it.isBlank() }) continue
            val user = userOf(r.getOrElse(u) { "" })
            val tv = amount(r.getOrElse(t) { "" })
            if (user.isEmpty() || tv == null || tv < 0) { skipped++; continue }
            byUser[user.lowercase()] = TargetRow(user, name?.let { r.getOrElse(it) { "" } }.orEmpty(), bc?.let { r.getOrElse(it) { "" } }.orEmpty(), bn?.let { r.getOrElse(it) { "" } }.orEmpty(), tv)
        }
        if (byUser.isEmpty()) throw IllegalArgumentException("No row has both a Username and a Target number.")
        return TargetResult(byUser.values.toList(), byUser.values.sumOf { it.target }, skipped)
    }

    fun parseCollection(table: List<List<String>>): CollectionResult {
        val (h, m) = header(table, listOf(listOf("collectiondate", "date"), listOf("collectionamount", "amount")), "Sale Collection report")
        val d = m["collectiondate"]!!; val a = m["collectionamount"]!!
        val ty = m["col:collectiontype"] ?: m["col:type"]
        val us = m["col:username"]
        val nm = m["col:name"]; val bc = m["col:basecode"]; val bn = m["col:basename"]
        val dc = m["col:dealercode"] ?: m["col:customercode"]; val dn = m["col:dealername"] ?: m["col:customername"]
        fun cell(r: List<String>, i: Int?) = if (i == null) "" else userOf(r.getOrElse(i) { "" })
        class Acc(var amount: Double = 0.0, var count: Int = 0)
        val acc = LinkedHashMap<String, Acc>()
        class Part(val date: String, val user: String, val type: String, val name: String, val bc: String, val bn: String, val dc: String, val dn: String)
        val parts = HashMap<String, Part>()
        val dealerSet = HashSet<String>()
        var fileRows = 0; var skipped = 0
        var from = ""; var to = ""; var total = 0.0; var unassigned = 0.0; var unassignedRows = 0
        val people = HashSet<String>(); val types = LinkedHashMap<String, Double>()
        for (i in h + 1 until table.size) {
            val r = table[i]
            if (r.all { it.isBlank() }) continue
            fileRows++
            val date = parseDate(r.getOrElse(d) { "" })
            val amt = amount(r.getOrElse(a) { "" })
            if (date == null || amt == null) { skipped++; continue }
            val user = if (us == null) "" else userOf(r.getOrElse(us) { "" })
            val type = (if (ty == null) "" else r.getOrElse(ty) { "" }).trim().ifEmpty { "Other" }
            val dCode = cell(r, dc); val dName = cell(r, dn)
            val k = "$date|${user.lowercase()}|$type|${dCode.lowercase()}|${if (dCode.isEmpty()) dName.lowercase() else ""}"
            if (dCode.isNotEmpty() || dName.isNotEmpty()) dealerSet.add(if (dCode.isNotEmpty()) dCode.lowercase() else dName.lowercase())
            val e = acc.getOrPut(k) { parts[k] = Part(date, user, type, cell(r, nm), cell(r, bc), cell(r, bn), dCode, dName); Acc() }
            e.amount += amt; e.count++
            if (from.isEmpty() || date < from) from = date
            if (to.isEmpty() || date > to) to = date
            total += amt; types[type] = (types[type] ?: 0.0) + amt
            if (user.isEmpty()) { unassigned += amt; unassignedRows++ } else people.add(user.lowercase())
        }
        if (acc.isEmpty()) throw IllegalArgumentException("No row has both a Collection Date and a Collection Amount.")
        val rows = acc.map { (k, v) -> val p = parts[k]!!; CollectionRow(p.date, p.user, p.type, Math.round(v.amount * 100) / 100.0, v.count, p.name, p.bc, p.bn, p.dc, p.dn) }
        return CollectionResult(rows, fileRows, from, to, total, unassigned, unassignedRows, people.size, skipped, types, dealerSet.size, dc != null || dn != null)
    }

    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    /** "06-Oct-2026", "2026-10-06", "06/10/2026" (day first) or an Excel date number -> "2026-10-06", or null. */
    fun parseDate(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        fun ok(y: Int, mo: Int, d: Int): String? {
            if (y !in 2000..2100 || mo !in 1..12 || d !in 1..31) return null
            val dim = intArrayOf(31, if (y % 4 == 0 && (y % 100 != 0 || y % 400 == 0)) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)[mo - 1]
            if (d > dim) return null
            return "%04d-%02d-%02d".format(y, mo, d)
        }
        Regex("^(\\d{1,2})[- ]([A-Za-z]{3})[A-Za-z]*[- ,]+(\\d{4})$").find(s)?.let {
            val mo = MONTHS.indexOf(it.groupValues[2].lowercase()) + 1
            return if (mo > 0) ok(it.groupValues[3].toInt(), mo, it.groupValues[1].toInt()) else null
        }
        Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})(?:[T ].*)?$").find(s)?.let { return ok(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }
        Regex("^(\\d{1,2})[/.](\\d{1,2})[/.](\\d{4})$").find(s)?.let { return ok(it.groupValues[3].toInt(), it.groupValues[2].toInt(), it.groupValues[1].toInt()) }
        if (Regex("^\\d{5}(\\.\\d+)?$").matches(s)) {                  // Excel serial number (days since 1899-12-30)
            val days = s.substringBefore('.').toLong() - 25569L        // days since 1970-01-01
            val z = days + 719468L
            val era = (if (z >= 0) z else z - 146096) / 146097
            val doe = z - era * 146097
            val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
            val y = yoe + era * 400
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val dd = (doy - (153 * mp + 2) / 5 + 1).toInt()
            val mm = (if (mp < 10) mp + 3 else mp - 9).toInt()
            return ok((if (mm <= 2) y + 1 else y).toInt(), mm, dd)
        }
        return null
    }
}

/** "৳ 1,234,567" (no decimals; a figure under 1,000 keeps its decimals). */
fun taka(d: Double): String {
    val neg = d < 0
    val a = Math.abs(d)
    val s = if (a < 1000 && a != Math.floor(a)) String.format(java.util.Locale.US, "%,.2f", a) else String.format(java.util.Locale.US, "%,.0f", a)
    return (if (neg) "-" else "") + "৳ " + s
}
