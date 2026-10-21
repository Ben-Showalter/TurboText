package com.turbotext.app

/**
 * Reads a customer list for a broadcast list out of a CSV file: one
 * person per row, a phone number in some column and optionally a name.
 * Copes with what spreadsheets export — quoted fields, `;` or tab
 * separators, a header row, a byte-order mark, first/last name columns.
 * Pure Kotlin, so it's unit tested.
 */
object CsvContacts {

    data class Result(
        /** One per number, in file order; name is "" when the file had none. */
        val contacts: List<BroadcastContact>,
        /** Rows with no usable phone number (header rows not counted). */
        val invalid: Int,
        /** Rows whose number was already earlier in the file. */
        val duplicates: Int
    )

    private val NAME_HEADERS = setOf("name", "full name", "fullname", "contact", "contact name", "customer", "customer name")
    private val FIRST_HEADERS = setOf("first", "first name", "firstname", "given name")
    private val LAST_HEADERS = setOf("last", "last name", "lastname", "surname", "family name")

    /** How numbers are compared: the last 10 digits (as OwnNumber.key). */
    fun key(number: String): String = number.filter { it.isDigit() }.takeLast(10)

    /** A phone number from one cell, or null when it isn't one: 7–15
     *  digits once spaces, dashes, dots, brackets and a leading + go.
     *  A US 11-digit number starting with 1 loses the 1. */
    fun phoneNumber(cell: String): String? {
        val trimmed = cell.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.any { !(it.isDigit() || it in " -.()+/") }) return null
        val digits = trimmed.filter { it.isDigit() }
        if (digits.length !in 7..15) return null
        return if (digits.length == 11 && digits.startsWith("1")) digits.substring(1) else digits
    }

    fun parse(text: String): Result {
        val rows = rows(text.removePrefix("﻿")).filter { row -> row.any { it.isNotBlank() } }
        if (rows.isEmpty()) return Result(emptyList(), 0, 0)

        val width = rows.maxOf { it.size }
        // The number column: where the most cells are phone numbers.
        val numberCol = (0 until width).maxByOrNull { col ->
            rows.count { row -> row.getOrNull(col)?.let(::phoneNumber) != null }
        } ?: 0

        val header = rows.first().map { it.trim().lowercase() }
        val hasHeader = phoneNumber(rows.first().getOrNull(numberCol) ?: "") == null &&
            header.any { it.isNotEmpty() && phoneNumber(it) == null }
        val firstCol = if (hasHeader) header.indexOfFirst { it in FIRST_HEADERS } else -1
        val lastCol = if (hasHeader) header.indexOfFirst { it in LAST_HEADERS } else -1
        val nameCol = when {
            hasHeader && header.indexOfFirst { it in NAME_HEADERS } >= 0 -> header.indexOfFirst { it in NAME_HEADERS }
            firstCol >= 0 -> -1
            // No header naming it: the first column that's mostly words.
            else -> (0 until width).firstOrNull { col ->
                col != numberCol && rows.count { row ->
                    row.getOrNull(col)?.let { it.any(Char::isLetter) } == true
                } * 2 >= rows.size - (if (hasHeader) 1 else 0)
            } ?: -1
        }

        val seen = HashSet<String>()
        val contacts = ArrayList<BroadcastContact>()
        var invalid = 0
        var duplicates = 0
        for (row in if (hasHeader) rows.drop(1) else rows) {
            val number = row.getOrNull(numberCol)?.let(::phoneNumber)
            if (number == null) {
                invalid++
                continue
            }
            if (!seen.add(key(number))) {
                duplicates++
                continue
            }
            val name = if (nameCol >= 0) {
                row.getOrNull(nameCol)?.trim().orEmpty()
            } else {
                listOf(firstCol, lastCol).filter { it >= 0 }
                    .mapNotNull { row.getOrNull(it)?.trim()?.takeIf(String::isNotEmpty) }
                    .joinToString(" ")
            }
            contacts.add(BroadcastContact(name, number))
        }
        return Result(contacts, invalid, duplicates)
    }

    /** Splits CSV text into rows of cells. The separator is whichever of
     *  `,` `;` or tab appears most in the first line. */
    internal fun rows(text: String): List<List<String>> {
        val firstLine = text.lineSequence().firstOrNull().orEmpty()
        val sep = listOf(',', ';', '\t').maxByOrNull { c -> firstLine.count { it == c } } ?: ','
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                quoted -> cell.append(c)
                c == sep -> { row.add(cell.toString()); cell.clear() }
                c == '\r' -> {}
                c == '\n' -> {
                    row.add(cell.toString()); cell.clear()
                    rows.add(row); row = ArrayList()
                }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row.add(cell.toString())
            rows.add(row)
        }
        return rows
    }

    /** [incoming] laid over [existing]: a number already in the list is
     *  replaced in place (its name updated, never listed twice), new
     *  numbers are added at the end. An imported row with no name keeps
     *  the name the list already had. Returns (list, added, updated). */
    fun merge(existing: List<BroadcastContact>, incoming: List<BroadcastContact>): Triple<List<BroadcastContact>, Int, Int> {
        val result = existing.toMutableList()
        val index = HashMap<String, Int>()
        result.forEachIndexed { i, c -> index.putIfAbsent(key(c.number), i) }
        var added = 0
        var updated = 0
        for (c in incoming) {
            val at = index[key(c.number)]
            if (at != null) {
                val old = result[at]
                result[at] = BroadcastContact(c.name.ifBlank { old.name }, c.number)
                updated++
            } else {
                index[key(c.number)] = result.size
                result.add(c)
                added++
            }
        }
        return Triple(result, added, updated)
    }
}
