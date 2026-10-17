package com.ble.rawreader

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * RAW 变化记录的存取。用系统自带的 SQLite（android.database.sqlite 属于 Android 系统 API，
 * 不算第三方依赖），好处是「超出上限删最旧」「按时间倒序」「分页加载」都能一条 SQL 搞定。
 *
 * 表结构：
 *   records(_id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, raw TEXT)
 *   - ts  毫秒时间戳（建了索引，用于倒序查询）
 *   - raw 十六进制字符串，存空格分隔格式，界面可直接展示
 *
 * 写入规则：
 *   1. 内存里缓存「数据库最新一条 RAW」，广播包来了先在内存比对，相同就直接返回，
 *      一次数据库操作都不做（广播包每秒能来好几个，必须挡在数据库外面）。
 *   2. 只有真的变化了才落库；第一条无条件插入。
 *   3. 插入后若总数超过 [MAX_RECORDS]，删掉最旧的若干条。
 */
object RecordStore {

    /** 记录条数上限 */
    const val MAX_RECORDS = 1000

    private const val DB_NAME = "raw_records.db"
    private const val DB_VERSION = 1

    private const val TABLE = "records"
    private const val COL_ID = "_id"
    private const val COL_TS = "ts"
    private const val COL_RAW = "raw"

    /** 数据库 helper 单例，整个进程只 new 一次 */
    @Volatile
    private var helperRef: DbHelper? = null

    /** 内存缓存：数据库里最新一条的 RAW，用于快速比对 */
    @Volatile
    private var cachedLastRaw: String? = null

    /** 缓存是否已从数据库加载过 */
    @Volatile
    private var cacheReady: Boolean = false

    /** 一条记录 */
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

    /** 预热：建库并把「最新一条 RAW」读进内存。可在主线程调，开销极小。 */
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

    /**
     * 收到一个新的 RAW，跟数据库最新一条比对，不同才写入。
     *
     * @return true 表示真的新插入了一条（调用方可以据此刷新条数）
     */
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

    /** 总数超过上限时，删掉最旧的若干条 */
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

    /** 当前记录总条数 */
    fun count(ctx: Context): Int {
        val total = DatabaseUtils.queryNumEntries(helper(ctx).readableDatabase, TABLE)
        return if (total > Int.MAX_VALUE) Int.MAX_VALUE else total.toInt()
    }

    /** 按时间倒序分页取记录：最新的一条在最前面 */
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

    /** 清空全部记录 */
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

    /** 数据库帮助类（单例持有） */
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
