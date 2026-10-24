package com.example.attendance

import android.content.Context

/**
 * The 8 divisions, 64 districts and their thanas, read from assets/bd_locations.txt (edit that file to change them).
 * A division is taken from the heading line before its districts: "# ---- Dhaka division ----".
 */
object BdLocations {
    private var data: Map<String, List<String>>? = null
    private var divisionOf: Map<String, String>? = null        // district -> division

    fun districts(ctx: Context): List<String> = load(ctx).keys.sorted()

    fun thanas(ctx: Context, district: String): List<String> = load(ctx)[district] ?: emptyList()

    fun divisions(ctx: Context): List<String> { load(ctx); return (divisionOf ?: emptyMap()).values.distinct().sorted() }

    fun districtsIn(ctx: Context, division: String): List<String> {
        load(ctx)
        return (divisionOf ?: emptyMap()).filterValues { it == division }.keys.sorted()
    }

    private fun load(ctx: Context): Map<String, List<String>> {
        data?.let { return it }
        val map = LinkedHashMap<String, List<String>>()
        val div = LinkedHashMap<String, String>()
        var currentDivision = ""
        ctx.assets.open("bd_locations.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.map { it.trim() }.forEach { line ->
                if (line.startsWith("#")) {
                    val m = Regex("^#\\s*-+\\s*(.+?)\\s+division\\s*-+", RegexOption.IGNORE_CASE).find(line)
                    if (m != null) currentDivision = m.groupValues[1].trim()
                } else if (line.isNotEmpty() && line.contains(":")) {
                    val name = line.substringBefore(":").trim()
                    val thanas = line.substringAfter(":").split(",").map { it.trim() }.filter { it.isNotEmpty() }.sorted()
                    if (name.isNotEmpty() && thanas.isNotEmpty()) {
                        map[name] = thanas
                        if (currentDivision.isNotEmpty()) div[name] = currentDivision
                    }
                }
            }
        }
        data = map
        divisionOf = div
        return map
    }
}
