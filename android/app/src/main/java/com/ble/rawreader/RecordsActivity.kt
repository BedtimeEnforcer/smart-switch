package com.ble.rawreader

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RAW 变化记录页。
 *
 * - 列表按时间倒序（最新在上），每行：MM-DD HH:mm:ss + RAW 值（单行截断）
 * - 点某一行 → 复制该条 RAW 到剪贴板
 * - 右上角「清空」，带二次确认
 * - 分页加载，每页 200 条，最多 1000 条
 */
class RecordsActivity : Activity() {

    companion object {
        /** 每页加载条数 */
        private const val PAGE_SIZE = 200
    }

    private lateinit var tvCount: TextView
    private lateinit var listRecords: ListView
    private lateinit var tvEmpty: TextView
    private lateinit var btnLoadMore: Button
    private lateinit var tvClear: TextView
    private lateinit var tvBack: TextView

    private lateinit var recordAdapter: RecordAdapter

    private val rows: ArrayList<RecordStore.Record> = ArrayList()

    private val stampFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_records)
        bindViews()
        recordAdapter = RecordAdapter()
        listRecords.adapter = recordAdapter
        setupEvents()
        reload()
    }

    private fun bindViews() {
        tvBack = findViewById(R.id.tvRecBack)
        tvClear = findViewById(R.id.tvRecClear)
        tvCount = findViewById(R.id.tvRecCount)
        listRecords = findViewById(R.id.listRecords)
        tvEmpty = findViewById(R.id.tvRecEmpty)
        btnLoadMore = findViewById(R.id.btnRecLoadMore)
    }

    private fun setupEvents() {
        tvBack.setOnClickListener { finish() }

        tvClear.setOnClickListener { confirmClear() }

        btnLoadMore.setOnClickListener { loadMore() }

        // 点一行复制该条 RAW
        listRecords.setOnItemClickListener { _, _, position, _ ->
            if (position in rows.indices) copyRaw(rows[position].raw)
        }
    }

    /** 重新加载第一页并刷新顶部计数 */
    private fun reload() {
        rows.clear()
        rows.addAll(RecordStore.loadPage(this, PAGE_SIZE, 0))
        recordAdapter.notifyDataSetChanged()
        refreshHeader()
    }

    /** 加载下一页，追加到列表尾部 */
    private fun loadMore() {
        val more = RecordStore.loadPage(this, PAGE_SIZE, rows.size)
        if (more.isEmpty()) {
            btnLoadMore.visibility = View.GONE
            return
        }
        rows.addAll(more)
        recordAdapter.notifyDataSetChanged()
        listRecords.setSelection(rows.size - more.size)
        refreshHeader()
    }

    private fun refreshHeader() {
        val total = RecordStore.count(this)
        tvCount.text = "共 $total 条 / 上限 ${RecordStore.MAX_RECORDS}"
        tvEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        btnLoadMore.visibility = if (rows.size < total) View.VISIBLE else View.GONE
        btnLoadMore.text = "加载更多（已显示 ${rows.size} / $total）"
    }

    private fun copyRaw(raw: String) {
        if (raw.isEmpty()) {
            Toast.makeText(this, "该条没有内容", Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("SwitchCode", raw))
        Toast.makeText(this, getString(R.string.copied), Toast.LENGTH_SHORT).show()
    }

    private fun confirmClear() {
        val total = RecordStore.count(this)
        if (total == 0) {
            Toast.makeText(this, "已经是空的了", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("清空记录")
            .setMessage("确定要删除全部 $total 条记录吗？删除后不可恢复。")
            .setPositiveButton("清空") { _, _ ->
                RecordStore.clear(this)
                reload()
                Toast.makeText(this, "记录已清空", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------------------------------------------------------- 列表适配器

    private inner class RecordAdapter : BaseAdapter() {

        override fun getCount(): Int = rows.size
        override fun getItem(position: Int): RecordStore.Record = rows[position]
        override fun getItemId(position: Int): Long = rows[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            // 复用 convertView，1000 条也不卡
            val v = convertView ?: layoutInflater.inflate(R.layout.item_record, parent, false)
            val holder = (v.tag as? ViewHolder) ?: ViewHolder(v).also { v.tag = it }
            val r = getItem(position)
            holder.tvTime.text = stampFmt.format(Date(r.ts))
            holder.tvRaw.text = r.raw
            return v
        }
    }

    private class ViewHolder(v: View) {
        val tvTime: TextView = v.findViewById(R.id.tvRecTime)
        val tvRaw: TextView = v.findViewById(R.id.tvRecRaw)
    }
}
