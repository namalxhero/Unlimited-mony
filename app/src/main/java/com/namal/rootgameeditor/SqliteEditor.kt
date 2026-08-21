package com.namal.rootgameeditor

import android.database.sqlite.SQLiteDatabase
import java.io.File

data class TableRow(val rowId: Long, val values: MutableMap<String, String?>)

class SqliteEditor(private val localDbFile: File) {

    private val db: SQLiteDatabase = SQLiteDatabase.openDatabase(
        localDbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE
    )

    fun listTables(): List<String> {
        val tables = mutableListOf<String>()
        db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'",
            null
        ).use { c ->
            while (c.moveToNext()) tables.add(c.getString(0))
        }
        return tables
    }

    fun columnNames(table: String): List<String> {
        val cols = mutableListOf<String>()
        db.rawQuery("PRAGMA table_info(\"$table\")", null).use { c ->
            val nameIdx = c.getColumnIndex("name")
            while (c.moveToNext()) cols.add(c.getString(nameIdx))
        }
        return cols
    }

    /** Reads up to [limit] rows. Uses SQLite's implicit rowid to allow editing. */
    fun readRows(table: String, limit: Int = 200): List<TableRow> {
        val cols = columnNames(table)
        val rows = mutableListOf<TableRow>()
        db.rawQuery("SELECT rowid, * FROM \"$table\" LIMIT $limit", null).use { c ->
            while (c.moveToNext()) {
                val rowId = c.getLong(c.getColumnIndexOrThrow("rowid"))
                val values = LinkedHashMap<String, String?>()
                for (col in cols) {
                    val idx = c.getColumnIndex(col)
                    values[col] = if (idx >= 0) c.getString(idx) else null
                }
                rows.add(TableRow(rowId, values))
            }
        }
        return rows
    }

    fun updateCell(table: String, rowId: Long, column: String, newValue: String) {
        db.execSQL(
            "UPDATE \"$table\" SET \"$column\" = ? WHERE rowid = ?",
            arrayOf(newValue, rowId)
        )
    }

    fun close() = db.close()
}
