package bd.desertfox.llmrouter

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {
    private val IMPORT_REQ = 11
    private val BACKUP_REQ = 12
    private val modelKeys = ArrayList<String>()
    private val modelIndex = HashMap<String, JSONObject>()
    private var cachedSortedKeys: List<String>? = null
    private var sortedKeysDirty = true
    private val provCounts = HashMap<String, Int>()
    private var modelQuery = ""
    private var curTab = 0
    private var lastLpFire = 0L
    private var selectMode = false
    private val selected = HashSet<String>()
    private var expandedCombo: String? = null

    private val BG = 0xFF0D0D11.toInt()
    private val CARD = 0xFF1B1B23.toInt()
    private val ROWBG = 0xFF20202A.toInt()
    private val TXT = 0xFFECECF1.toInt()
    private val DIM = 0xFF8F8FA0.toInt()
    private val ACCENT = 0xFF6EE7B7.toInt()
    private val RED = 0xFFFF6B6B.toInt()
    private val PILL_OFF = 0xFF2A2A33.toInt()

    private lateinit var statusLine: TextView
    private lateinit var activeLine: TextView
    private lateinit var serverPill: TextView
    private lateinit var provArea: LinearLayout
    private lateinit var modelsList: ListView
    private lateinit var searchBox: EditText
    private lateinit var pPanel: LinearLayout
    private lateinit var mPanel: LinearLayout
    private lateinit var activePanel: LinearLayout
    private lateinit var comboArea: LinearLayout
    private lateinit var modelsRow: LinearLayout
    private lateinit var drawer: LinearLayout
    private lateinit var scrim: View
    private lateinit var drawerServer: TextView
    private var drawerShown = false
    private val tabs = ArrayList<LinearLayout>()
    private val tabBars = ArrayList<View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashCatcher()
        Store.load(applicationContext)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContentView(buildUi())
        showTab(0)
        showLastCrashIfAny()
    }

    private fun installCrashCatcher() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                java.io.File(filesDir, "crash_last.txt").writeText(
                    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date()) + "\n" +
                        e.stackTraceToString().take(2500))
            } catch (ignored: Exception) {}
            prev?.uncaughtException(t, e)
        }
    }

    private fun showLastCrashIfAny() {
        try {
            val f = java.io.File(filesDir, "crash_last.txt")
            if (!f.exists() || f.length() == 0L) return
            val txt = f.readText()
            f.delete()
            AlertDialog.Builder(this)
                .setTitle("Last crash")
                .setMessage(txt.take(1500))
                .setPositiveButton("OK", null)
                .show()
        } catch (ignored: Exception) {}
    }

    override fun onResume() { super.onResume(); refresh() }

    override fun onPause() {
        // Flush any debounced Store.save() so the last 2s of state
        // (logs, model results) are not lost if the process is killed.
        Store.saveNow()
        super.onPause()
    }

    override fun onBackPressed() {
        if (drawerShown) setDrawer(false) else super.onBackPressed()
    }

    // Cached medium typeface: was created fresh for every row/view.
    private val mediumTypeface: Typeface by lazy { Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    // Cached card backgrounds, one GradientDrawable per color (never
    // mutated after creation, so sharing is safe).
    private val cardBgCache = HashMap<Int, GradientDrawable>()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun cardBg(c: Int): GradientDrawable {
        val cached = cardBgCache[c]
        if (cached != null) return cached
        val gd = GradientDrawable()
        gd.cornerRadius = dp(12).toFloat()
        gd.setColor(c)
        cardBgCache[c] = gd
        return gd
    }

    private fun textBtn(label: String, onTap: () -> Unit): TextView {
        val t = TextView(this)
        t.text = label
        t.textSize = 13.5f
        t.typeface = mediumTypeface
        t.setTextColor(ACCENT)
        t.gravity = android.view.Gravity.CENTER
        t.setPadding(dp(12), dp(9), dp(12), dp(9))
        t.background = cardBg(CARD)
        t.isClickable = true
        t.setOnClickListener { onTap() }
        return t
    }

    private fun space(w: Int): View {
        val v = View(this)
        v.layoutParams = LinearLayout.LayoutParams(dp(w), 1)
        return v
    }

    private fun row(): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.background = cardBg(CARD)
        c.setPadding(dp(14), dp(11), dp(14), dp(11))
        c.layoutParams = AbsListView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return c
    }

    private fun colText(c: LinearLayout, txt: String, size: Float, color: Int, bold: Boolean): TextView {
        val t = TextView(this)
        t.text = txt
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.typeface = mediumTypeface
        c.addView(t)
        return t
    }

    // ================= build =================

    private fun buildUi(): View {
        val rootFrame = FrameLayout(this)
        rootFrame.setBackgroundColor(BG)

        val main = LinearLayout(this)
        main.orientation = LinearLayout.VERTICAL
        main.setBackgroundColor(BG)
        main.setPadding(dp(16), dp(20), dp(16), dp(12))

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = android.view.Gravity.CENTER_VERTICAL
        val burger = TextView(this)
        burger.text = "\u2261"
        burger.textSize = 22f
        burger.setTextColor(ACCENT)
        burger.setPadding(dp(2), dp(2), dp(14), dp(2))
        burger.isClickable = true
        burger.setOnClickListener { setDrawer(!drawerShown) }
        head.addView(burger)
        val title = TextView(this)
        title.text = "LLM Router"
        title.textSize = 21f
        title.typeface = mediumTypeface
        title.setTextColor(TXT)
        head.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        serverPill = TextView(this)
        serverPill.textSize = 13f
        serverPill.typeface = mediumTypeface
        serverPill.setPadding(dp(16), dp(7), dp(16), dp(7))
        serverPill.isClickable = true
        serverPill.setOnClickListener { toggleServer() }
        head.addView(serverPill)
        main.addView(head)

        statusLine = TextView(this)
        statusLine.textSize = 12f
        statusLine.setTextColor(DIM)
        statusLine.setPadding(0, dp(6), 0, 0)
        main.addView(statusLine)

        activeLine = TextView(this)
        activeLine.textSize = 12f
        activeLine.setTextColor(ACCENT)
        activeLine.setPadding(0, dp(2), 0, dp(10))
        main.addView(activeLine)

        val tabRow = LinearLayout(this)
        tabRow.orientation = LinearLayout.HORIZONTAL
        val names = arrayOf("Providers", "Models", "Active")
        for (i in names.indices) {
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.gravity = android.view.Gravity.CENTER_HORIZONTAL
            val t = TextView(this)
            t.text = names[i]
            t.textSize = 14f
            t.setPadding(0, dp(8), 0, dp(6))
            t.gravity = android.view.Gravity.CENTER
            val bar = View(this)
            bar.layoutParams = LinearLayout.LayoutParams(dp(28), dp(3))
            col.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            col.addView(bar)
            col.isClickable = true
            col.setOnClickListener { showTab(i) }
            tabs.add(col)
            tabBars.add(bar)
            tabRow.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        main.addView(tabRow)

        val frame = FrameLayout(this)
        frame.setBackgroundColor(BG)

        // ---- providers panel ----
        pPanel = LinearLayout(this)
        pPanel.orientation = LinearLayout.VERTICAL
        pPanel.setPadding(0, dp(10), 0, 0)
        val provScroll = ScrollView(this)
        provScroll.isVerticalScrollBarEnabled = false
        provArea = LinearLayout(this)
        provArea.orientation = LinearLayout.VERTICAL
        provArea.setPadding(dp(2), 0, dp(2), 0)
        provScroll.addView(provArea, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        pPanel.addView(provScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val pRow = LinearLayout(this)
        pRow.orientation = LinearLayout.HORIZONTAL
        pRow.setPadding(0, dp(10), 0, 0)
        pRow.addView(textBtn("+ Add") { providerDialog() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        pRow.addView(space(dp(8)))
        pRow.addView(textBtn("Import") { pickImport() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        pRow.addView(space(dp(8)))
        pRow.addView(textBtn("Fetch all") { fetchModels() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        pPanel.addView(pRow)
        frame.addView(pPanel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // ---- models panel ----
        mPanel = LinearLayout(this)
        mPanel.orientation = LinearLayout.VERTICAL
        mPanel.setPadding(0, dp(10), 0, 0)
        searchBox = EditText(this)
        searchBox.hint = "search model..."
        searchBox.setSingleLine(true)
        searchBox.textSize = 14f
        searchBox.setTextColor(TXT)
        searchBox.setHintTextColor(DIM)
        searchBox.setPadding(dp(12), dp(10), dp(12), dp(10))
        searchBox.background = cardBg(ROWBG)
        val sbp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        sbp.setMargins(dp(2), 0, dp(2), dp(8))
        mPanel.addView(searchBox, sbp)
        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                modelQuery = (s?.toString() ?: "").trim()
                rebuildModelKeys()
                (modelsList.adapter as BaseAdapter).notifyDataSetChanged()
            }
        })
        modelsList = ListView(this)
        modelsList.divider = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        modelsList.dividerHeight = dp(6)
        modelsList.setPadding(dp(2), 0, dp(2), 0)
        modelsList.adapter = ModelAdapter()
        modelsList.setOnItemClickListener { _, _, pos, _ ->
            if (pos < modelKeys.size && selectMode) {
                val key = modelKeys[pos]
                if (selected.contains(key)) selected.remove(key) else selected.add(key)
                refresh()
            }
        }
        modelsList.setOnItemLongClickListener { _, _, pos, _ ->
            if (pos < modelKeys.size && !selectMode) lpGuard { modelLongPress(modelKeys[pos]) }
            true
        }
        mPanel.addView(modelsList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        modelsRow = LinearLayout(this)
        modelsRow.orientation = LinearLayout.HORIZONTAL
        modelsRow.setPadding(0, dp(10), 0, 0)
        mPanel.addView(modelsRow)
        frame.addView(mPanel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // ---- active panel (combos, collapsed -> tap to expand) ----
        activePanel = LinearLayout(this)
        activePanel.orientation = LinearLayout.VERTICAL
        activePanel.setPadding(0, dp(10), 0, 0)
        val comboScroll = ScrollView(this)
        comboScroll.isVerticalScrollBarEnabled = false
        comboArea = LinearLayout(this)
        comboArea.orientation = LinearLayout.VERTICAL
        comboArea.setPadding(dp(2), 0, dp(2), 0)
        comboScroll.addView(comboArea, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        activePanel.addView(comboScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val cRow = LinearLayout(this)
        cRow.orientation = LinearLayout.HORIZONTAL
        cRow.setPadding(0, dp(10), 0, 0)
        cRow.addView(textBtn("+ Add combo") { newComboDialog() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        cRow.addView(space(dp(8)))
        cRow.addView(textBtn("Deactivate") { Store.clearActive(); refresh() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        cRow.addView(space(dp(8)))
        cRow.addView(textBtn("Log") { logDialog() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        activePanel.addView(cRow)
        frame.addView(activePanel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        main.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        rootFrame.addView(main, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // ---- drawer (lightweight, no library) ----
        scrim = View(this)
        scrim.setBackgroundColor(0x88000000.toInt())
        scrim.isClickable = true
        scrim.setOnClickListener { setDrawer(false) }
        scrim.visibility = View.GONE
        rootFrame.addView(scrim, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        drawer = LinearLayout(this)
        drawer.orientation = LinearLayout.VERTICAL
        drawer.setBackgroundColor(0xFF16161D.toInt())
        val dhead = TextView(this)
        dhead.text = "LLM Router"
        dhead.textSize = 17f
        dhead.typeface = mediumTypeface
        dhead.setTextColor(TXT)
        dhead.setPadding(dp(18), dp(22), dp(18), dp(2))
        drawer.addView(dhead)
        val dver = TextView(this)
        dver.text = "v0.4.0"
        dver.textSize = 12f
        dver.setTextColor(DIM)
        dver.setPadding(dp(18), 0, dp(18), dp(10))
        drawer.addView(dver)
        drawer.addView(dDivider())
        drawerServer = TextView(this)
        drawerServer.textSize = 15f
        drawerServer.setTextColor(TXT)
        drawerServer.setPadding(dp(18), dp(14), dp(18), dp(14))
        drawerServer.isClickable = true
        drawerServer.setOnClickListener { toggleServer() }
        drawer.addView(drawerServer)
        drawer.addView(dDivider())
        drawer.addView(dItem("Import keys (keys.json)") { pickImport() })
        drawer.addView(dItem("Fetch all models") { fetchModels() })
        drawer.addView(dItem("Backup config") { backupConfig() })
        drawer.addView(dDivider())
        drawer.addView(dItem("About / server info") { aboutDialog() })
        drawer.visibility = View.GONE
        rootFrame.addView(drawer, FrameLayout.LayoutParams(dp(272), ViewGroup.LayoutParams.MATCH_PARENT))

        return rootFrame
    }

    private fun dDivider(): View {
        val v = View(this)
        v.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        v.setBackgroundColor(0xFF26262F.toInt())
        return v
    }

    private fun dItem(label: String, onTap: () -> Unit): TextView {
        val t = TextView(this)
        t.text = label
        t.textSize = 15f
        t.setTextColor(TXT)
        t.setPadding(dp(18), dp(14), dp(18), dp(14))
        t.isClickable = true
        t.setOnClickListener { setDrawer(false); onTap() }
        return t
    }

    private fun setDrawer(open: Boolean) {
        drawerShown = open
        drawer.visibility = if (open) View.VISIBLE else View.GONE
        scrim.visibility = if (open) View.VISIBLE else View.GONE
    }

    private fun toggleServer() {
        if (HttpServerHolder.running) {
            stopService(Intent(this, RouterService::class.java))
        } else {
            startForegroundService(Intent(this, RouterService::class.java))
        }
        serverPill.postDelayed({ refresh() }, 500)
    }

    // ================= adapters =================

    private abstract inner class Rows : BaseAdapter() {
        override fun getCount(): Int = rowCount()
        override fun getItem(p: Int): Any? = null
        override fun getItemId(p: Int): Long = p.toLong()
        override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
            val c = (cv as? LinearLayout) ?: row()
            fill(c, p)
            return c
        }
        abstract fun rowCount(): Int
        abstract fun fill(c: LinearLayout, p: Int)
    }

    private fun lpGuard(action: () -> Unit) {
        val now = System.currentTimeMillis()
        if (now - lastLpFire < 700) return
        lastLpFire = now
        action()
    }

    private fun buildProvArea() {
        provArea.removeAllViews()
        val ps = Store.providers()
        if (ps.length() == 0) {
            val t = TextView(this)
            t.text = "no providers \u2014 Import \u09a6\u09bf\u09df\u09c7 keys \u0986\u09a8\u09cb"
            t.textSize = 13f
            t.setTextColor(DIM)
            t.setPadding(dp(4), dp(8), dp(4), dp(8))
            provArea.addView(t)
            return
        }
        for (i in 0 until ps.length()) {
            val prov = ps.getJSONObject(i)
            val name = prov.optString("name")
            val card = comboCard()
            val head = LinearLayout(this)
            head.orientation = LinearLayout.HORIZONTAL
            head.gravity = android.view.Gravity.CENTER_VERTICAL
            val en = prov.optBoolean("enabled", true)
            val t = TextView(this)
            t.text = name + (if (!en) "  (disabled)" else "")
            t.textSize = 15.5f
            t.setTextColor(if (en) TXT else DIM)
            t.typeface = mediumTypeface
            head.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val chip = TextView(this)
            chip.text = "" + (provCounts[name] ?: 0)
            chip.textSize = 12f
            chip.setTextColor(DIM)
            chip.setPadding(dp(8), dp(2), dp(8), dp(2))
            chip.background = cardBg(0xFF24242E.toInt())
            head.addView(chip)
            card.addView(head)
            colText(card, prov.optString("baseUrl"), 12f, DIM, false)
            // Visible actions (used to be long-press-only, so users never
            // found them): refresh this provider's models, or delete it.
            val btnRow = LinearLayout(this)
            btnRow.orientation = LinearLayout.HORIZONTAL
            btnRow.setPadding(0, dp(8), 0, 0)
            val refreshBtn = textBtn("Refresh") { refreshProvider(name) }
            btnRow.addView(refreshBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            btnRow.addView(space(dp(8)))
            val deleteBtn = textBtn("Delete") {
                AlertDialog.Builder(this)
                    .setTitle("Delete " + name + "?")
                    .setPositiveButton("Delete") { _, _ -> Store.removeProvider(name); refresh() }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            deleteBtn.setTextColor(RED)
            btnRow.addView(deleteBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(btnRow)
            // tap: nothing; long press: menu
            card.isLongClickable = true
            card.setOnLongClickListener { lpGuard { providerLongPress(name) }; true }
            provArea.addView(card)
        }
    }

    private inner class ModelAdapter : Rows() {
        override fun rowCount(): Int = modelKeys.size
        override fun fill(c: LinearLayout, p: Int) {
            val key = modelKeys[p]
            val m = modelIndex[key] ?: return
            c.removeAllViews()
            val head = LinearLayout(this@MainActivity)
            head.orientation = LinearLayout.HORIZONTAL
            head.gravity = android.view.Gravity.CENTER_VERTICAL
            if (selectMode) {
                val dot = TextView(this@MainActivity)
                dot.text = if (selected.contains(key)) "\u25C9" else "\u25CB"
                dot.textSize = 17f
                dot.setTextColor(if (selected.contains(key)) ACCENT else DIM)
                dot.setPadding(0, 0, dp(8), 0)
                head.addView(dot)
            }
            val t = TextView(this@MainActivity)
            t.text = m.optString("id")
            t.textSize = 15f
            t.setTextColor(TXT)
            t.typeface = mediumTypeface
            head.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val chip = TextView(this@MainActivity)
            val enabled = m.optBoolean("enabled", true)
            val (label, col) = when {
                !enabled -> Pair("disabled", DIM)
                m.has("lastOk") && m.optBoolean("lastOk") -> Pair("" + m.optLong("lastLatencyMs") + "ms", ACCENT)
                m.has("lastOk") -> Pair("fail", RED)
                else -> Pair("untested", DIM)
            }
            chip.text = label
            chip.textSize = 12f
            chip.setTextColor(col)
            chip.setPadding(dp(8), dp(2), dp(8), dp(2))
            chip.background = cardBg(0xFF24242E.toInt())
            head.addView(chip)
            c.addView(head)
            colText(c, m.optString("provider") + (if (m.has("lastError") && !m.optBoolean("lastOk", true) && enabled)
                " \u00b7 " + m.optString("lastError").take(48) else ""), 12f, DIM, false)
            c.isLongClickable = true
            c.setOnLongClickListener {
                if (!selectMode) lpGuard { modelLongPress(key) }
                true
            }
        }
    }

    // ================= tab switching =================

    private fun showTab(i: Int) {
        curTab = i
        for (j in 0 until tabs.size) {
            val t = tabs[j].getChildAt(0) as TextView
            if (j == i) {
                t.setTextColor(ACCENT)
                t.typeface = mediumTypeface
                tabBars[j].visibility = View.VISIBLE
            } else {
                t.setTextColor(DIM)
                t.typeface = Typeface.DEFAULT
                tabBars[j].visibility = View.INVISIBLE
            }
        }
        pPanel.visibility = if (i == 0) View.VISIBLE else View.GONE
        mPanel.visibility = if (i == 1) View.VISIBLE else View.GONE
        activePanel.visibility = if (i == 2) View.VISIBLE else View.GONE
        refresh()
    }

    // ================= model keys + search =================

    private fun sortedModelKeys(): List<String> {
        val ms = Store.models()
        val list = ArrayList<Triple<String, JSONObject, Int>>()
        for (i in 0 until ms.length()) {
            val m = ms.getJSONObject(i)
            val key = m.optString("provider") + "|" + m.optString("id")
            val enabled = m.optBoolean("enabled", true)
            val rank = when {
                !enabled -> 3
                m.has("lastOk") && m.optBoolean("lastOk") -> 0
                m.has("lastOk") -> 2
                else -> 1
            }
            list.add(Triple(key, m, rank))
        }
        list.sortWith(compareBy(
            { it.third },
            { if (it.second.optBoolean("lastOk", false)) it.second.optLong("lastLatencyMs", Long.MAX_VALUE) else Long.MAX_VALUE }))
        return list.map { it.first }
    }

    private fun rebuildModelKeys() {
        modelIndex.clear()
        val ms = Store.models()
        for (i in 0 until ms.length()) {
            val m = ms.getJSONObject(i)
            modelIndex[m.optString("provider") + "|" + m.optString("id")] = m
        }
        // Sort once, filter per keystroke: the sort used to run on every
        // keystroke in the search box. Re-sorted only when dirty (see
        // refresh(), which runs after any model change).
        if (sortedKeysDirty || cachedSortedKeys == null) {
            cachedSortedKeys = sortedModelKeys()
            sortedKeysDirty = false
        }
        modelKeys.clear()
        val q = modelQuery.lowercase()
        val hf = Store.cfg.optBoolean("hideFailed", false)
        for (k in cachedSortedKeys!!) {
            val m = modelIndex[k] ?: continue
            if (q.isNotBlank() &&
                !(m.optString("id").lowercase().contains(q) || m.optString("provider").lowercase().contains(q))) continue
            if (hf && m.has("lastOk") && !m.optBoolean("lastOk")) continue
            modelKeys.add(k)
        }
    }

    private fun rebuildModelsRow() {
        modelsRow.removeAllViews()
        if (selectMode) {
            val n = selected.size
            modelsRow.addView(textBtn("Activate " + n) { activateSelected() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            modelsRow.addView(space(dp(8)))
            modelsRow.addView(textBtn("Test sel") { testBatch(ArrayList(selected)) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            modelsRow.addView(space(dp(8)))
            modelsRow.addView(textBtn("Done") { selectMode = false; selected.clear(); refresh() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        } else {
            modelsRow.addView(textBtn("Test all") { testAll() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            modelsRow.addView(space(dp(8)))
            modelsRow.addView(textBtn("Select") { selectMode = true; selected.clear(); refresh() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            modelsRow.addView(space(dp(8)))
            modelsRow.addView(textBtn("Log") { logDialog() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    // ================= refresh + combo area =================

    private fun refresh() {
        sortedKeysDirty = true
        provCounts.clear()
        val ps = Store.providers()
        val msAll = Store.models()
        for (i in 0 until msAll.length()) {
            val pn = msAll.getJSONObject(i).optString("provider")
            provCounts[pn] = (provCounts[pn] ?: 0) + 1
        }
        rebuildModelKeys()
        (modelsList.adapter as BaseAdapter).notifyDataSetChanged()
        if (curTab == 0) buildProvArea()
        if (curTab == 2) buildComboArea()

        val on = HttpServerHolder.running
        serverPill.text = if (on) "ON" else "OFF"
        serverPill.background = cardBg(if (on) 0xFF1F3D33.toInt() else PILL_OFF)
        serverPill.setTextColor(if (on) ACCENT else DIM)
        if (this::drawerServer.isInitialized) {
            drawerServer.text = if (on) "Server: ON" else "Server: OFF"
        }
        statusLine.text = "http://127.0.0.1:" + Store.cfg.optInt("port", 8790) + "/v1  \u00b7  " +
            ps.length() + " providers \u00b7 " + modelKeys.size + " models \u00b7 " + Store.combos().length() + " combos"
        val raw = Store.activeKey()
        activeLine.text = when {
            raw == "none" -> "Active: none"
            raw.length > 100 -> {
                val parts = raw.split(" -> ")
                "Active: " + parts.first() + "  \u2026  +" + (parts.size - 1) + " more"
            }
            else -> "Active: " + raw
        }
        rebuildModelsRow()
    }

    private fun comboCard(): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.background = cardBg(CARD)
        c.setPadding(dp(14), dp(11), dp(14), dp(11))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, 0, 0, dp(6))
        c.layoutParams = lp
        return c
    }

    private fun buildComboArea() {
        comboArea.removeAllViews()
        val cs = Store.combos()
        if (cs.length() == 0) {
            val t = TextView(this)
            t.text = "no combos yet \u2014 \"+ Add combo\" \u0964"
            t.textSize = 13f
            t.setTextColor(DIM)
            t.setPadding(dp(4), dp(8), dp(4), dp(8))
            comboArea.addView(t)
            return
        }
        for (i in 0 until cs.length()) {
            val combo = cs.getJSONObject(i)
            val name = combo.optString("name")
            if (name.isBlank()) continue
            val ids = combo.optJSONArray("models") ?: JSONArray()
            val active = Store.activeComboName() == name
            val card = comboCard()
            val head = LinearLayout(this)
            head.orientation = LinearLayout.HORIZONTAL
            head.gravity = android.view.Gravity.CENTER_VERTICAL
            val chev = TextView(this)
            chev.text = if (expandedCombo == name) "\u25BE" else "\u25B8"
            chev.textSize = 14f
            chev.setTextColor(ACCENT)
            chev.setPadding(0, 0, dp(8), 0)
            head.addView(chev)
            val t = TextView(this)
            t.text = name
            t.textSize = 15.5f
            t.setTextColor(TXT)
            t.typeface = mediumTypeface
            head.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val chip = TextView(this)
            chip.text = if (active) "ACTIVE \u00b7 " + ids.length() else "" + ids.length() + " models"
            chip.textSize = 12f
            chip.setTextColor(if (active) 0xFF0D0D11.toInt() else DIM)
            chip.setPadding(dp(8), dp(3), dp(8), dp(3))
            chip.background = cardBg(if (active) ACCENT else 0xFF24242E.toInt())
            head.addView(chip)
            card.addView(head)
            // tap = expand/collapse; long press = menu
            head.isClickable = true
            head.isLongClickable = true
            head.setOnClickListener { expandedCombo = if (expandedCombo == name) null else name; refresh() }
            head.setOnLongClickListener { comboMenu(name); true }
            if (expandedCombo == name) buildExpanded(card, name)
            comboArea.addView(card)
        }
    }

    private fun buildExpanded(card: LinearLayout, name: String) {
        val keys = ArrayList(Store.comboKeys(name))
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.setPadding(0, dp(8), 0, 0)
        val ROWH = dp(42)
        for (i in keys.indices) {
            val rIdx = i
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = android.view.Gravity.CENTER_VERTICAL
            r.background = cardBg(ROWBG)
            r.setPadding(dp(10), 0, dp(10), 0)
            r.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ROWH)
            val key = keys[i]
            val m = modelIndex[key]
            val t = TextView(this)
            t.text = if (m != null) m.optString("id") else key
            t.textSize = 14.5f
            t.setTextColor(TXT)
            t.maxLines = 1
            r.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            // Reorder with visible buttons (the old drag-to-reorder kept a
            // shared mutable list + a delayed Runnable that raced refresh()
            // and crashed). Index is resolved at tap time; first row has no
            // up, last row has no down.
            val up = TextView(this)
            up.text = "\u25B2"
            up.textSize = 13f
            up.setTextColor(if (rIdx == 0) DIM else ACCENT)
            up.setPadding(dp(8), dp(6), dp(4), dp(6))
            up.isClickable = rIdx != 0
            up.setOnClickListener {
                val idx = keys.indexOf(key).let { if (it < 0) rIdx else it }
                if (idx > 0 && Store.comboMove(name, idx, -1)) refresh()
            }
            r.addView(up)
            val down = TextView(this)
            down.text = "\u25BC"
            down.textSize = 13f
            down.setTextColor(if (rIdx == keys.size - 1) DIM else ACCENT)
            down.setPadding(dp(4), dp(6), dp(8), dp(6))
            down.isClickable = rIdx != keys.size - 1
            down.setOnClickListener {
                val idx = keys.indexOf(key).let { if (it < 0) rIdx else it }
                if (idx >= 0 && idx < keys.size - 1 && Store.comboMove(name, idx, +1)) refresh()
            }
            r.addView(down)
            val x = TextView(this)
            x.text = "\u2715"
            x.textSize = 14f
            x.setTextColor(RED)
            x.setPadding(dp(10), dp(6), dp(6), dp(6))
            x.isClickable = true
            x.setOnClickListener {
                Store.comboRemoveAt(name, keys.indexOf(key).let { if (it < 0) rIdx else it })
                refresh()
            }
            r.addView(x)
            wrap.addView(r)
        }
        card.addView(wrap)
        val act = TextView(this)
        act.text = "Activate"
        act.textSize = 13.5f
        act.typeface = mediumTypeface
        act.setTextColor(if (keys.isEmpty()) DIM else ACCENT)
        act.gravity = android.view.Gravity.CENTER
        act.background = cardBg(CARD)
        act.setPadding(dp(12), dp(9), dp(12), dp(9))
        act.isClickable = keys.isNotEmpty()
        val alp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        alp.setMargins(0, dp(8), 0, 0)
        act.layoutParams = alp
        act.setOnClickListener {
            if (Store.comboKeys(name).isNotEmpty()) {
                Store.activateCombo(name)
                Toast.makeText(this, "Activated: " + name, Toast.LENGTH_SHORT).show()
                refresh()
            }
        }
        card.addView(act)
    }

    // ================= dialogs / actions =================

    private fun comboMenu(name: String) {
        val opts = arrayOf("Add models", "Sort speed", "Rename", "Delete")
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(opts) { _, w ->
                when (opts[w]) {
                    "Add models" -> addModelsDialog(name)
                    "Sort speed" -> { Store.comboSortBySpeed(name); refresh() }
                    "Rename" -> renameDialog(name)
                    "Delete" -> deleteCombo(name)
                }
            }
            .show()
    }

    private fun newComboDialog() {
        val box = EditText(this)
        box.hint = "combo name"
        AlertDialog.Builder(this)
            .setTitle("New combo")
            .setView(box)
            .setPositiveButton("Create") { _, _ ->
                val n = box.text.toString().trim()
                if (Store.addCombo(n)) { expandedCombo = n; refresh() }
                else Toast.makeText(this, "Name taken or empty", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renameDialog(old: String) {
        if (old.isBlank()) return
        val box = EditText(this)
        box.setText(old)
        AlertDialog.Builder(this)
            .setTitle("Rename combo")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val n = box.text.toString().trim()
                if (Store.renameCombo(old, n)) { if (expandedCombo == old) expandedCombo = n; refresh() }
                else Toast.makeText(this, "Name taken or empty", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteCombo(name: String) {
        if (name.isBlank()) return
        AlertDialog.Builder(this)
            .setTitle("Delete " + name + "?")
            .setPositiveButton("Delete") { _, _ ->
                Store.removeCombo(name)
                if (expandedCombo == name) expandedCombo = null
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addModelsDialog(name: String) {
        if (name.isBlank()) return
        val all = sortedModelKeys()
        if (all.isEmpty()) { Toast.makeText(this, "No models yet, fetch first", Toast.LENGTH_SHORT).show(); return }
        val old = HashSet(Store.comboKeys(name))
        val checked = HashMap<String, Boolean>()
        for (k in all) checked[k] = old.contains(k)
        var rows = ArrayList(all)

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(10), dp(20), 0)
        val search = EditText(this)
        search.hint = "search model..."
        search.setSingleLine(true)
        search.textSize = 14f
        search.setTextColor(TXT)
        search.setHintTextColor(DIM)
        search.setPadding(dp(12), dp(10), dp(12), dp(10))
        search.background = cardBg(ROWBG)
        box.addView(search)
        val list = ListView(this)
        list.divider = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        list.dividerHeight = dp(2)
        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = rows.size
            override fun getItem(p: Int): Any? = null
            override fun getItemId(p: Int): Long = p.toLong()
            override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
                val key = rows[p]
                val c = (cv as? LinearLayout) ?: run {
                    val l = LinearLayout(this@MainActivity)
                    l.orientation = LinearLayout.HORIZONTAL
                    l.gravity = android.view.Gravity.CENTER_VERTICAL
                    l.setPadding(dp(8), dp(9), dp(8), dp(9))
                    l.layoutParams = AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    val dot = TextView(this@MainActivity)
                    dot.textSize = 16f
                    dot.setPadding(0, 0, dp(10), 0)
                    l.addView(dot)
                    val t = TextView(this@MainActivity)
                    t.textSize = 14.5f
                    t.setTextColor(TXT)
                    t.maxLines = 1
                    l.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    val pv = TextView(this@MainActivity)
                    pv.textSize = 11.5f
                    pv.setTextColor(DIM)
                    l.addView(pv)
                    l
                }
                val m = modelIndex[key]
                val dot = c.getChildAt(0) as TextView
                dot.text = if (checked[key] == true) "\u25C9" else "\u25CB"
                dot.setTextColor(if (checked[key] == true) ACCENT else DIM)
                val t = c.getChildAt(1) as TextView
                t.text = if (m != null) m.optString("id") else key
                val pv = c.getChildAt(2) as TextView
                pv.text = key.substringBefore("|")
                c.setOnClickListener {
                    checked[key] = !(checked[key] ?: false)
                    notifyDataSetChanged()
                }
                return c
            }
        }
        list.adapter = adapter
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = (s?.toString() ?: "").trim().lowercase()
                rows = ArrayList(all.filter { k -> q.isBlank() || k.lowercase().contains(q) })
                adapter.notifyDataSetChanged()
            }
        })
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360))
        lp.setMargins(0, dp(10), 0, 0)
        box.addView(list, lp)
        AlertDialog.Builder(this)
            .setTitle("Add models to " + name)
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val out = ArrayList<String>()
                for (k in Store.comboKeys(name)) if (checked[k] == true) out.add(k)
                for (k in all) if (checked[k] == true && !out.contains(k)) out.add(k)
                Store.comboSet(name, out)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun providerLongPress(name: String) {
        val p = Store.providerByName(name) ?: return
        val enabled = p.optBoolean("enabled", true)
        val toggle = if (enabled) "Disable" else "Enable"
        val opts = arrayOf("Update models", toggle, "Rename", "Delete")
        AlertDialog.Builder(this)
            .setTitle(name)
            .setMessage(p.optString("baseUrl"))
            .setItems(opts) { _, w ->
                when (opts[w]) {
                    "Update models" -> refreshProvider(name)
                    toggle -> { Store.setProviderEnabled(name, !enabled); refresh() }
                    "Rename" -> renameProviderDialog(name)
                    "Delete" -> AlertDialog.Builder(this)
                        .setTitle("Delete " + name + "?")
                        .setPositiveButton("Delete") { _, _ -> Store.removeProvider(name); refresh() }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            .show()
    }

    private fun renameProviderDialog(old: String) {
        val box = EditText(this)
        box.setText(old)
        AlertDialog.Builder(this)
            .setTitle("Rename provider")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val n = box.text.toString().trim()
                if (Store.renameProvider(old, n)) refresh()
                else Toast.makeText(this, "Empty or name taken", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun modelLongPress(key: String) {
        val m = Store.findModel(key) ?: return
        val enabled = m.optBoolean("enabled", true)
        val hide = Store.cfg.optBoolean("hideFailed", false)
        val hideOpt = if (hide) "Show failed models" else "Hide all failed models"
        val opts = if (enabled)
            arrayOf("Activate single", "Add to combo", "Test this model", "Disable", hideOpt)
        else arrayOf("Re-enable", "Test this model", hideOpt)
        AlertDialog.Builder(this)
            .setTitle(m.optString("id") + " (" + m.optString("provider") + ")")
            .setItems(opts) { _, w ->
                when (opts[w]) {
                    "Activate single" -> Store.setActiveSingle(m.optString("provider"), m.optString("id"))
                    "Add to combo" -> chooseComboFor(key)
                    "Test this model" -> testBatch(arrayListOf(key))
                    "Disable" -> { m.put("enabled", false); Store.save() }
                    "Re-enable" -> { m.put("enabled", true); m.put("failStreak", 0); Store.save() }
                    hideOpt -> { Store.cfg.put("hideFailed", !hide); Store.save() }
                }
                refresh()
            }
            .show()
    }

    private fun chooseComboFor(key: String) {
        val cs = Store.combos()
        val names = ArrayList<String>()
        for (i in 0 until cs.length()) names.add(cs.getJSONObject(i).optString("name"))
        val items = names.toTypedArray() + arrayOf("+ New combo\u2026")
        AlertDialog.Builder(this)
            .setTitle("Add to which combo?")
            .setItems(items) { _, w ->
                if (w == names.size) {
                    val box = EditText(this)
                    AlertDialog.Builder(this)
                        .setTitle("New combo")
                        .setView(box)
                        .setPositiveButton("Create") { _, _ ->
                            val n = box.text.toString().trim()
                            if (Store.addCombo(n)) { Store.comboAddModel(n, key); expandedCombo = n; refresh() }
                            else Toast.makeText(this, "Name taken or empty", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else {
                    Store.comboAddModel(names[w], key)
                    Toast.makeText(this, "Added to " + names[w], Toast.LENGTH_SHORT).show()
                    refresh()
                }
            }
            .show()
    }

    private fun activateSelected() {
        if (selected.isEmpty()) { Toast.makeText(this, "Nothing selected", Toast.LENGTH_SHORT).show(); return }
        if (selected.size == 1) {
            val key = selected.iterator().next()
            val parts = key.split("|")
            if (parts.size >= 2) Store.setActiveSingle(parts[0], key.substringAfter("|"))
            selectMode = false
            selected.clear()
            refresh()
            return
        }
        val keys = ArrayList(selected)
        val box = EditText(this)
        box.hint = "combo name"
        box.setText("combo " + (Store.combos().length() + 1))
        AlertDialog.Builder(this)
            .setTitle("Activate " + keys.size + " models as combo")
            .setView(box)
            .setPositiveButton("Activate") { _, _ ->
                val n = box.text.toString().trim()
                if (Store.addCombo(n)) {
                    Store.comboSet(n, keys)
                    Store.activateCombo(n)
                    selectMode = false
                    selected.clear()
                    refresh()
                } else Toast.makeText(this, "Name taken or empty", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun providerDialog() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val pad = dp(20)
        box.setPadding(pad, dp(8), pad, 0)
        val name = EditText(this); name.hint = "name"
        val url = EditText(this); url.hint = "base url (https://.../v1)"
        val key = EditText(this); key.hint = "api key"
        box.addView(name); box.addView(url); box.addView(key)
        AlertDialog.Builder(this)
            .setTitle("Add provider")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                if (Store.addProvider(name.text.toString(), url.text.toString(), key.text.toString())) refresh()
                else Toast.makeText(this, "Duplicate or empty", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshProvider(name: String) {
        val p = Store.providerByName(name) ?: return
        Toast.makeText(this, "Refreshing " + name + "\u2026", Toast.LENGTH_SHORT).show()
        Thread {
            var added = 0
            var removed = 0
            var err = ""
            try {
                val list = ProviderClient.listModels(p)
                val r = Store.refreshProviderModels(name, list)
                added = r.first
                removed = r.second
            } catch (e: Exception) { err = (e.message ?: e.toString()).take(80) }
            val a = added
            val rm = removed
            val e2 = err
            runOnUiThread {
                if (e2.isNotBlank()) Toast.makeText(this, "Refresh failed: " + e2, Toast.LENGTH_LONG).show()
                else Toast.makeText(this, "+" + a + " new, -" + rm + " removed", Toast.LENGTH_LONG).show()
                refresh()
            }
        }.start()
    }

    private fun pickImport() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        startActivityForResult(i, IMPORT_REQ)
    }

    private fun backupConfig() {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "application/json"
        i.putExtra(Intent.EXTRA_TITLE, "llmrouter-config.json")
        startActivityForResult(i, BACKUP_REQ)
    }

    private fun fetchModels() {
        Toast.makeText(this, "Fetching models\u2026", Toast.LENGTH_SHORT).show()
        Thread {
            val providers = ArrayList<JSONObject>()
            val ps = Store.providers()
            for (i in 0 until ps.length()) {
                val p = ps.getJSONObject(i)
                if (p.optBoolean("enabled", true)) providers.add(p)
            }
            val added = java.util.concurrent.atomic.AtomicInteger(0)
            val removed = java.util.concurrent.atomic.AtomicInteger(0)
            val failed = java.util.concurrent.atomic.AtomicInteger(0)
            // Parallel fetch (4 threads — network-bound, this is a phone).
            // Each provider's list is fetched concurrently, then merged via
            // refreshProviderModels, which adds new models AND removes stale
            // ones (the old loop was sequential and add-only). The Store
            // merge is synchronized so concurrent merges can't lose each
            // other's updates.
            val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
            for (p in providers) {
                val name = p.optString("name")
                pool.submit {
                    try {
                        val list = ProviderClient.listModels(p)
                        val r = synchronized(Store) { Store.refreshProviderModels(name, list) }
                        added.addAndGet(r.first)
                        removed.addAndGet(r.second)
                    } catch (e: Exception) { failed.incrementAndGet() }
                }
            }
            pool.shutdown()
            pool.awaitTermination(10, java.util.concurrent.TimeUnit.MINUTES)
            val a = added.get()
            val rm = removed.get()
            val f = failed.get()
            runOnUiThread {
                Toast.makeText(this, "+" + a + " new, -" + rm + " removed, " + f + " providers failed",
                    Toast.LENGTH_LONG).show()
                refresh()
            }
        }.start()
    }

    private fun chatProbe(m: JSONObject): Triple<Boolean, Long, String> {
        val p = Store.providerByName(m.optString("provider")) ?: return Triple(false, 0L, "no provider")
        val t0 = System.currentTimeMillis()
        val body = JSONObject().put("model", m.optString("id"))
            .put("messages", JSONArray().put(JSONObject()
                .put("role", "user").put("content", "hi")))
            .put("max_tokens", 1)
        var ok = false
        var err = ""
        try {
            val r = ProviderClient.chat(p, m.optString("id"), body, false, 60000)
            ok = r.status in 200..299
            if (!ok) err = String(r.body ?: ByteArray(0), Charsets.UTF_8).take(120)
            r.close()
        } catch (e: Exception) { err = (e.message ?: e.toString()).take(120) }
        return Triple(ok, System.currentTimeMillis() - t0, err)
    }

    private fun testBatch(keysIn: List<String>) {
        if (keysIn.isEmpty()) { Toast.makeText(this, "Nothing selected", Toast.LENGTH_SHORT).show(); return }
        Toast.makeText(this, "Testing " + keysIn.size + " models\u2026", Toast.LENGTH_SHORT).show()
        Thread {
            val results = java.util.Collections.synchronizedList(ArrayList<Triple<String, Boolean, Long>>())
            val errs = java.util.Collections.synchronizedMap(HashMap<String, String>())
            val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
            for (k in keysIn) {
                pool.submit {
                    val m = Store.findModel(k) ?: return@submit
                    val r = chatProbe(m)
                    results.add(Triple(k, r.first, r.second))
                    if (r.third.isNotBlank()) errs[k] = r.third
                }
            }
            pool.shutdown()
            pool.awaitTermination(15, java.util.concurrent.TimeUnit.MINUTES)
            for (r in results) {
                val m = Store.findModel(r.first)
                Store.bumpModel(r.first, r.second, r.third, errs[r.first] ?: "")
                if (m != null) Store.addLog(LogEntry(System.currentTimeMillis(), m.optString("provider"),
                    m.optString("id"), if (r.second) 200 else 0, r.third, errs[r.first] ?: ""))
            }
            val n = results.size
            runOnUiThread {
                Toast.makeText(this, "" + n + " models tested", Toast.LENGTH_SHORT).show()
                refresh()
            }
        }.start()
    }

    private fun testAll() {
        val keys = ArrayList(modelKeys)
        testBatch(keys)
    }

    private fun logDialog() {
        val sb = StringBuilder()
        val logs = synchronized(Store.log) { Store.log.takeLast(60).reversed() }
        for (e in logs) {
            val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(e.ts))
            sb.append(t).append("  ").append(e.model).append("  ").append(e.status)
                .append("  ").append(e.latencyMs).append("ms")
            if (e.error.isNotBlank()) sb.append("\n   ").append(e.error.take(90))
            sb.append("\n")
        }
        AlertDialog.Builder(this).setTitle("Request log (last 60)")
            .setMessage(if (sb.isBlank()) "empty" else sb.toString())
            .setPositiveButton("OK", null).show()
    }

    private fun aboutDialog() {
        val ps = Store.providers()
        val msg = "Base URL: http://127.0.0.1:" + Store.cfg.optInt("port", 8790) + "/v1\n" +
            "API key: any value\n\n" +
            ps.length() + " providers \u00b7 " + Store.models().length() + " models \u00b7 " +
            Store.combos().length() + " combos\n\nLLM Router v0.4.0\nsmall \u00b7 offline \u00b7 no tracking"
        AlertDialog.Builder(this).setTitle("About").setMessage(msg)
            .setPositiveButton("OK", null).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        try {
            val uri: Uri = data.data ?: return
            if (requestCode == IMPORT_REQ) {
                val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return
                val n = Store.importKeys(text)
                Toast.makeText(this, "" + n + " providers imported", Toast.LENGTH_LONG).show()
                refresh()
            } else if (requestCode == BACKUP_REQ) {
                contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(Store.cfg.toString(2).toByteArray(Charsets.UTF_8))
                    os.flush()
                }
                Toast.makeText(this, "Backup saved", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed: " + e.message, Toast.LENGTH_LONG).show()
        }
    }
}
