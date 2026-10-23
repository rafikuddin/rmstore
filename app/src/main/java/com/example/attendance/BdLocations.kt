package com.example.attendance

import android.content.Context

/** The 64 districts and their thanas, read from assets/bd_locations.txt (edit that file to change them). */
object BdLocations {
    private var data: Map<String, List<String>>? = null

    fun districts(ctx: Context): List<String> = load(ctx).keys.sorted()

    fun thanas(ctx: Context, district: String): List<String> = load(ctx)[district] ?: emptyList()

    private fun load(ctx: Context): Map<String, List<String>> {
        data?.let { return it }
        val map = LinkedHashMap<String, List<String>>()
        ctx.assets.open("bd_locations.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains(":") }
                .forEach { line ->
                    val name = line.substringBefore(":").trim()
                    val thanas = line.substringAfter(":").split(",").map { it.trim() }.filter { it.isNotEmpty() }.sorted()
                    if (name.isNotEmpty() && thanas.isNotEmpty()) map[name] = thanas
                }
        }
        data = map
        return map
    }
}
