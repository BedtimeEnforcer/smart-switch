package com.ble.rawreader

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * RAW 变化记录的存取，用系统自带的 SQLite，不引第三方库。
 * 表 records(_id, ts, raw)，raw 存空格分隔的十六进制串。
 * 广播包每秒能来好几个，所以先在内存里跟最新一条比对，变了才落库；
 * 超过 MAX_RECORDS 条就删最旧的。
 */
object RecordStore {

    const val MAX_RECORDS = 1000

    private const val DB_NAME = "raw_records.db"
    private const val DB_VERSION = 1

    private const val TABLE = "records"
    private const val COL_ID = "_id"
    private const val COL_TS = "ts"
    private const val COL_RAW = "raw"

    @Volatile
    private var helperRef: DbHelper? = null

    @Volatile
    private var cachedLastRaw: String? = null

    @Volatile
    private var cacheReady: Boolean = false

    data class Record(
        val id: Long,
        val ts: Long,
        val raw: String
    )

    private fun helper(ctx: Context): DbHelper {
        val h = helperRef
        if (h != null) return h
        return synchronized(this) {
            helperRef ?: DbHelper(ctx.applicationContext).also { helperRef = it }
        }
    }

    // 预热：建库并把最新一条 RAW 读进内存，主线程调也没事
    fun init(ctx: Context) {
        ensureLastRaw(ctx)
    }

    private fun ensureLastRaw(ctx: Context) {
        if (cacheReady) return
        synchronized(this) {
            if (cacheReady) return
            val db = helper(ctx).readableDatabase
            var last: String? = null
            db.query(
                TABLE, arrayOf(COL_RAW), null, null, null, null,
                "$COL_ID DESC", "1"
            ).use { c ->
                if (c.moveToFirst()) last = c.getString(0)
            }
            cachedLastRaw = last
            cacheReady = true
        }
    }

    // 跟最新一条比对，不同才写入；返回 true 表示真的插入了一条
    fun appendIfChanged(ctx: Context, raw: String): Boolean {
        val value = raw.trim()
        if (value.isEmpty()) return false

        ensureLastRaw(ctx)
        if (value == cachedLastRaw) return false

        val db = helper(ctx).writableDatabase
        var inserted = false
        db.beginTransaction()
        try {
            val cv = ContentValues()
            cv.put(COL_TS, System.currentTimeMillis())
            cv.put(COL_RAW, value)
            val rowId = db.insert(TABLE, null, cv)
            if (rowId > 0) {
                trimToLimit(db)
                db.setTransactionSuccessful()
                inserted = true
            }
        } catch (e: Exception) {
            inserted = false
        } finally {
            db.endTransaction()
        }

        if (inserted) cachedLastRaw = value
        return inserted
    }

    private fun trimToLimit(db: SQLiteDatabase) {
        val total = DatabaseUtils.queryNumEntries(db, TABLE)
        if (total <= MAX_RECORDS) return
        val extra = total - MAX_RECORDS
        db.delete(
            TABLE,
            "$COL_ID IN (SELECT $COL_ID FROM $TABLE ORDER BY $COL_ID ASC LIMIT ?)",
            arrayOf(extra.toString())
        )
    }

    fun count(ctx: Context): Int {
        val total = DatabaseUtils.queryNumEntries(helper(ctx).readableDatabase, TABLE)
        return if (total > Int.MAX_VALUE) Int.MAX_VALUE else total.toInt()
    }

    fun loadPage(ctx: Context, limit: Int, offset: Int): List<Record> {
        val out = ArrayList<Record>(limit.coerceAtLeast(0))
        if (limit <= 0) return out
        val db = helper(ctx).readableDatabase
        db.query(
            TABLE,
            arrayOf(COL_ID, COL_TS, COL_RAW),
            null, null, null, null,
            "$COL_TS DESC, $COL_ID DESC",
            "$limit OFFSET $offset"
        ).use { c ->
            val iId = c.getColumnIndexOrThrow(COL_ID)
            val iTs = c.getColumnIndexOrThrow(COL_TS)
            val iRaw = c.getColumnIndexOrThrow(COL_RAW)
            while (c.moveToNext()) {
                out.add(Record(c.getLong(iId), c.getLong(iTs), c.getString(iRaw)))
            }
        }
        return out
    }

    fun clear(ctx: Context) {
        val db = helper(ctx).writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE, null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        cachedLastRaw = null
        cacheReady = true
    }

    private class DbHelper(ctx: Context) : SQLiteOpenHelper(ctx, DB_NAME, null, DB_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS $TABLE (" +
                    "$COL_ID INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "$COL_TS INTEGER NOT NULL, " +
                    "$COL_RAW TEXT NOT NULL)"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_${TABLE}_ts ON $TABLE($COL_TS)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // 目前只有版本 1，升级路径预留：先删旧表再按最新结构重建
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            onCreate(db)
        }
    }
}
