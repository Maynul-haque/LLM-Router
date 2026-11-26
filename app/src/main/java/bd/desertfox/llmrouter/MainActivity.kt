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
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
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

    // A pass younger than this is "fresh" — Test All skips it.
    private val PASS_FRESH_MS = 24L * 60 * 60 * 1000
    // A passed model slower than this counts as slow for Hide slow.
    private val SLOW_MS = 3000L

    // Models added by the last refresh/fetch, not yet tested. Set on
    // the UI thread only; Test new models / Test all clear it.
    private var pendingNewTestKeys = ArrayList<String>()

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
    private lateinit var provSearchBox: EditText
    private var provQuery = ""
    private var expandedProvider: String? = null
    private lateinit var modelsList: ListView
    private lateinit var searchBox: EditText
    private lateinit var progressRow: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var testNewRow: LinearLayout
    private lateinit var testNewBtn: TextView
    @Volatile private var testing = false
    @Volatile private var fetching = false
    private lateinit var pPanel: LinearLayout
    private lateinit var mPanel: LinearLayout
    private lateinit var activePanel: LinearLayout
    private lateinit var comboArea: LinearLayout
    private lateinit var modelsRow: LinearLayout
    private lateinit var drawer: LinearLayout
    private lateinit var scrim: View
    private lateinit var drawerAutoTest: TextView
    private var drawerShown = false
    // Log dialog filter state, kept across opens.
    private var logFailedOnly = false
    private var logFilterText = ""
    private val tabs = ArrayList<LinearLayout>()
    private val tabBars = ArrayList<View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashCatcher()
        try {
            Store.ensureLoaded(applicationContext)
            // Left/right swipe anywhere switches Providers / Models /
            // Combos. Horizontal fling only, so vertical scrolling
            // is untouched; ignored while the drawer is open.
            // Observed in dispatchTouchEvent (not consumed) so
            // lists, rows and search boxes keep working as before.
            tabSwipe = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
                override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                    if (e1 == null || drawerShown) return false
                    val dx = e2.x - e1.x
                    if (Math.abs(dx) < dp(80).toFloat() || Math.abs(dx) < Math.abs(e2.y - e1.y) * 1.5f) return false
                    if (dx < 0 && curTab < 2) showTab(curTab + 1)
                    else if (dx > 0 && curTab > 0) showTab(curTab - 1)
                    return true
                }
            })
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
            setContentView(buildUi())
            showTab(0)
            showLastCrashIfAny()
        } catch (e: Throwable) {
            // Never die on launch without saying why: show the
            // error on a plain screen the user can copy from and
            // send in. (The crash catcher also saved it to
            // crash_last.txt, but that dialog can never appear
            // when the app dies before the UI exists.)
            try {
                java.io.File(filesDir, "crash_last.txt").writeText(
                    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date()) + "\n" +
                        e.stackTraceToString().take(2500))
            } catch (ignored: Exception) {}
            val tv = TextView(this)
            tv.text = "LLM Router could not start.\n\nLong-press to copy this text and send it to the developer:\n\n" +
                e.stackTraceToString().take(2000)
            tv.setTextIsSelectable(true)
            tv.setTextColor(TXT)
            tv.textSize = 13f
            tv.setPadding(dp(16), dp(16), dp(16), dp(16))
            setContentView(tv)
        }
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
                // Copy sends the whole stored log (up to 2500 chars),
                // not just the 1500 shown in the dialog, so the full
                // stack trace can be pasted into a bug report.
                .setNeutralButton("Copy") { _, _ ->
                    val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("LLM Router crash log", txt))
                    Toast.makeText(this, "Crash log copied", Toast.LENGTH_SHORT).show()
                }
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
        provSearchBox = EditText(this)
        provSearchBox.hint = "search provider..."
        provSearchBox.setSingleLine(true)
        provSearchBox.textSize = 14f
        provSearchBox.setTextColor(TXT)
        provSearchBox.setHintTextColor(DIM)
        provSearchBox.setPadding(dp(12), dp(10), dp(12), dp(10))
        provSearchBox.background = cardBg(ROWBG)
        val pbp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        pbp.setMargins(dp(2), 0, dp(2), dp(8))
        pPanel.addView(provSearchBox, pbp)
        provSearchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                provQuery = (s?.toString() ?: "").trim()
                buildProvArea()
            }
        })
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
        // Quick scroll: the list can hold hundreds of models, so the
        // fast-scroll thumb is always visible for dragging through it.
        modelsList.isFastScrollEnabled = true
        modelsList.isFastScrollAlwaysVisible = true
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
        // Small progress row at the bottom of the Models tab, shown while
        // a test or fetch is running ("Testing 7/24").
        progressRow = LinearLayout(this)
        progressRow.orientation = LinearLayout.HORIZONTAL
        progressRow.gravity = android.view.Gravity.CENTER_VERTICAL
        progressRow.setPadding(dp(2), dp(10), dp(2), 0)
        progressRow.visibility = View.GONE
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progressBar.max = 100
        progressBar.progress = 0
        progressRow.addView(progressBar, LinearLayout.LayoutParams(0, dp(6), 1f))
        progressRow.addView(space(dp(10)))
        progressText = TextView(this)
        progressText.textSize = 12f
        progressText.setTextColor(DIM)
        progressRow.addView(progressText)
        mPanel.addView(progressRow)
        // Row with the "Test new models (N)" button, shown only when a
        // refresh/fetch added models that haven't been tested yet.
        testNewRow = LinearLayout(this)
        testNewRow.orientation = LinearLayout.HORIZONTAL
        testNewRow.setPadding(0, dp(10), 0, 0)
        testNewRow.visibility = View.GONE
        testNewBtn = textBtn("Test new models") {
            if (testing || fetching) {
                Toast.makeText(this, "Already running, please wait", Toast.LENGTH_SHORT).show()
                return@textBtn
            }
            val keys = ArrayList(pendingNewTestKeys)
            pendingNewTestKeys.clear()
            testNewRow.visibility = View.GONE
            testBatch(keys)
        }
        testNewRow.addView(testNewBtn,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        mPanel.addView(testNewRow)
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
        dver.text = "v0.5.9"
        dver.textSize = 12f
        dver.setTextColor(DIM)
        dver.setPadding(dp(18), 0, dp(18), dp(10))
        drawer.addView(dver)
        drawer.addView(dDivider())
        drawer.addView(dItem("Import keys (keys.json)") { pickImport() })
        drawer.addView(dItem("Backup config") { backupConfig() })
        drawer.addView(dDivider())
        drawer.addView(dItem("Request log") { logDialog() })
        drawer.addView(dItem("Change port\u2026") { portDialog() })
        drawerAutoTest = dItem(autoTestLabel()) { toggleAutoTest() }
        drawer.addView(drawerAutoTest)
        drawer.addView(dDivider())
        drawer.addView(dItem("How to use") { howToDialog() })
        drawer.addView(dItem("About") { aboutDialog() })
        drawer.addView(dItem("Server info") { serverDialog() })
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

    // ---- drawer actions: port, auto-test ----

    private fun autoTestLabel(): String =
        "Auto-test new models: " + if (Store.cfg.optBoolean("autoTestNew", false)) "ON" else "OFF"

    private fun toggleAutoTest() {
        val on = !Store.cfg.optBoolean("autoTestNew", false)
        Store.cfg.put("autoTestNew", on)
        Store.save()
        Toast.makeText(this, if (on) "New models will be tested automatically after a fetch"
            else "Auto-test off — use \"Test new models\" instead", Toast.LENGTH_LONG).show()
        refresh()
    }

    // Change the server port from the UI. The running server is
    // restarted on the new port (stop, brief pause so the old socket
    // is released, start); the base URL in your app must be updated
    // to match, so the toast says so.
    private fun portDialog() {
        val box = EditText(this)
        box.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        box.setText("" + Store.cfg.optInt("port", 8790))
        box.hint = "1024 - 65535"
        AlertDialog.Builder(this).setTitle("Server port").setView(box)
            .setPositiveButton("Save") { _, _ ->
                val p = box.text.toString().trim().toIntOrNull()
                if (p == null || p < 1024 || p > 65535) {
                    Toast.makeText(this, "Port must be 1024-65535", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                Store.cfg.put("port", p)
                Store.save()
                if (HttpServerHolder.running) {
                    stopService(Intent(this, RouterService::class.java))
                    serverPill.postDelayed({
                        startForegroundService(Intent(this, RouterService::class.java))
                        serverPill.postDelayed({ refresh() }, 500)
                    }, 600)
                } else refresh()
                Toast.makeText(this, "Port set to " + p + " — update the base URL in your app",
                    Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Cancel", null).show()
    }

    // After a fetch/refresh, test the newly added models right away
    // when the auto-test toggle is on. If a batch is already running,
    // leave the pending set alone — the "Test new models" button
    // stays, instead of losing the list.
    private fun maybeAutoTestNew() {
        if (!Store.cfg.optBoolean("autoTestNew", false)) return
        if (pendingNewTestKeys.isEmpty()) return
        if (testing) return
        val keys = ArrayList(pendingNewTestKeys)
        pendingNewTestKeys.clear()
        testBatch(keys)
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
        val q = provQuery.lowercase()
        var shown = 0
        for (i in 0 until ps.length()) {
            val prov = ps.getJSONObject(i)
            val name = prov.optString("name")
            if (q.isNotBlank() &&
                !(name.lowercase().contains(q) || prov.optString("baseUrl").lowercase().contains(q))) continue
            shown++
            val card = comboCard()
            val head = LinearLayout(this)
            head.orientation = LinearLayout.HORIZONTAL
            head.gravity = android.view.Gravity.CENTER_VERTICAL
            // Green chevron like the combo cards: tap to expand/collapse.
            // The Refresh/Delete buttons live inside the expanded card,
            // so the list stays compact when there are many providers.
            val expanded = expandedProvider == name
            val chev = TextView(this)
            chev.text = if (expanded) "\u25BE" else "\u25B8"
            chev.textSize = 14f
            chev.setTextColor(ACCENT)
            chev.setPadding(0, 0, dp(8), 0)
            head.addView(chev)
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
            if (expanded) {
                // All provider actions live here now — long-press is
                // disabled for the moment, so nothing is hidden in a menu.
                val btnRow = LinearLayout(this)
                btnRow.orientation = LinearLayout.HORIZONTAL
                btnRow.setPadding(0, dp(8), 0, 0)
                val refreshBtn = textBtn("Refresh") { refreshProvider(name) }
                btnRow.addView(refreshBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                btnRow.addView(space(dp(8)))
                val disableBtn = textBtn(if (en) "Disable" else "Enable") {
                    Store.setProviderEnabled(name, !en); refresh()
                }
                if (!en) disableBtn.setTextColor(ACCENT)
                btnRow.addView(disableBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                card.addView(btnRow)
                val btnRow2 = LinearLayout(this)
                btnRow2.orientation = LinearLayout.HORIZONTAL
                btnRow2.setPadding(0, dp(8), 0, 0)
                val renameBtn = textBtn("Rename") { renameProviderDialog(name) }
                btnRow2.addView(renameBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                btnRow2.addView(space(dp(8)))
                val deleteBtn = textBtn("Delete") {
                    AlertDialog.Builder(this)
                        .setTitle("Delete " + name + "?")
                        .setPositiveButton("Delete") { _, _ ->
                            if (expandedProvider == name) expandedProvider = null
                            Store.removeProvider(name)
                            refresh()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                deleteBtn.setTextColor(RED)
                btnRow2.addView(deleteBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                card.addView(btnRow2)
            }
            // tap header: expand/collapse. Long-press is disabled for
            // now — every action is in the expanded card instead.
            head.isClickable = true
            head.setOnClickListener { expandedProvider = if (expandedProvider == name) null else name; buildProvArea() }
            provArea.addView(card)
        }
        if (shown == 0) {
            val t = TextView(this)
            t.text = "no providers match \"" + provQuery + "\""
            t.textSize = 13f
            t.setTextColor(DIM)
            t.setPadding(dp(4), dp(8), dp(4), dp(8))
            provArea.addView(t)
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
            // Hidden models show a "hidden" chip (they only appear when
            // Hide failed is off); a 429-cooled model shows "cooldown"
            // so it is clear why the router skips it for the next minute.
            val (label, col) = when {
                m.optBoolean("hidden", false) -> Pair("hidden", DIM)
                Store.isCooledDown(key) -> Pair("cooldown", DIM)
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

    // Swipe detector, created in onCreate (not as a field
    // initializer) so nothing runs before the activity exists.
    private var tabSwipe: GestureDetector? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        tabSwipe?.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
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
                m.optBoolean("hidden", false) -> 3
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

    // "Unusable" for the Hide-failed filter: failed the last test,
    // disabled (by hand, or auto-disabled after 3 fails), or its
    // provider is disabled. Paid models count as unusable too: they
    // cost money this key shouldn't spend, so Hide failed hides them
    // and the combo selector excludes them. Any of those means
    // RikkaHub can't use it.
    private fun isUnusable(m: JSONObject): Boolean {
        if (m.optBoolean("hidden", false)) return true
        if (!m.optBoolean("enabled", true)) return true
        if (m.optBoolean("paid", false)) return true
        val p = Store.providerByName(m.optString("provider"))
        if (p != null && !p.optBoolean("enabled", true)) return true
        return m.has("lastOk") && !m.optBoolean("lastOk")
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
        val hs = Store.cfg.optBoolean("hideSlow", false)
        for (k in cachedSortedKeys!!) {
            val m = modelIndex[k] ?: continue
            if (q.isNotBlank() &&
                !(m.optString("id").lowercase().contains(q) || m.optString("provider").lowercase().contains(q))) continue
            if (hf && isUnusable(m)) continue
            // Hide slow is a display filter only: passed models slower
            // than SLOW_MS are hidden from the list, nothing else
            // changes (they stay enabled, usable and in combos).
            if (hs && m.optBoolean("lastOk", false) && m.optLong("lastLatencyMs", 0L) > SLOW_MS) continue
            modelKeys.add(k)
        }
    }

    private fun rebuildModelsRow() {
        modelsRow.removeAllViews()
        if (selectMode) {
            val n = selected.size
            // One model selected = activate it as a single model (a
            // one-model combo would be pointless); two or more = the
            // button makes a combo and activates it.
            modelsRow.addView(textBtn(if (n == 1) "Activate" else "Activate " + n) { activateSelected() },
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
            // Visible Hide-all toggle (it used to live only in the
            // long-press menu, so most users never found it). When on,
            // every unusable model is hidden from the list; tap again
            // to show them. Label + green colour show the current state.
            val hideOn = Store.cfg.optBoolean("hideFailed", false)
            val hideBtn = textBtn(if (hideOn) "Show failed" else "Hide failed") {
                Store.cfg.put("hideFailed", !hideOn); Store.save(); refresh()
            }
            if (hideOn) hideBtn.setTextColor(ACCENT)
            modelsRow.addView(hideBtn,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            modelsRow.addView(space(dp(8)))
            // Hide slow: same toggle style as Hide failed. Hides only
            // passed models slower than SLOW_MS — a display filter, so
            // it never disables anything or touches combos.
            val slowOn = Store.cfg.optBoolean("hideSlow", false)
            val slowBtn = textBtn(if (slowOn) "Show slow" else "Hide slow") {
                Store.cfg.put("hideSlow", !slowOn); Store.save(); refresh()
            }
            if (slowOn) slowBtn.setTextColor(ACCENT)
            modelsRow.addView(slowBtn,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            modelsRow.addView(space(dp(8)))
            modelsRow.addView(textBtn("Select") { selectMode = true; selected.clear(); refresh() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    // ================= progress row (test / fetch) =================
    // All three run on the UI thread only; workers post via runOnUiThread.
    private fun showProgress(label: String, total: Int) {
        progressBar.max = total.coerceAtLeast(1)
        progressBar.progress = 0
        progressText.text = label + " 0/" + total
        progressRow.visibility = View.VISIBLE
    }

    private fun updateProgress(label: String, done: Int, total: Int) {
        progressBar.max = total.coerceAtLeast(1)
        progressBar.progress = done.coerceAtMost(total)
        progressText.text = label + " " + done + "/" + total
        if (progressRow.visibility != View.VISIBLE) progressRow.visibility = View.VISIBLE
    }

    private fun hideProgress() { progressRow.visibility = View.GONE }

    // ================= refresh + combo area =================

    private fun refresh() {
        sortedKeysDirty = true
        // Keep the single auto-managed "auto-best" combo in sync
        // with the models (diff-guarded inside, so this is cheap
        // in steady state).
        Store.rebuildAutoBest()
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
        if (this::drawerAutoTest.isInitialized) {
            drawerAutoTest.text = autoTestLabel()
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
        // "Test new models" row: visible only while there are models
        // added by a refresh/fetch that haven't been tested yet, and
        // not while multi-select is open.
        if (pendingNewTestKeys.isNotEmpty() && !selectMode) {
            testNewBtn.text = "Test new models (" + pendingNewTestKeys.size + ")"
            testNewRow.visibility = View.VISIBLE
        } else {
            testNewRow.visibility = View.GONE
        }
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
            // Combo health: how many of its models are actually usable
            // right now (passed, enabled, not hidden, not paid) out of
            // the total — so a combo with 3 dead models out of 15
            // reads "12/15" instead of just "15 models".
            val total = ids.length()
            var usable = 0
            for (j in 0 until total) if (Store.isModelUsable(ids.optString(j))) usable++
            val isAuto = Store.isAutoManaged(name)
            chip.text = if (active) "ACTIVE \u00b7 " + usable + "/" + total
                else if (isAuto) "auto \u00b7 " + usable + "/" + total + " usable"
                else "" + usable + "/" + total + " usable"
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
        // Auto-managed combos are read-only: rows have no
        // reorder/remove buttons (the rebuild owns the list).
        val readOnly = Store.isAutoManaged(name)
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
            // Unusable models in a combo are greyed out with the reason
            // next to the name (same rule as the Models tab chip), so the
            // "15/17 usable" count can be traced to the actual models.
            // The ▲▼✕ buttons stay working, so a dead model can still
            // be moved down or removed.
            val usable = Store.isModelUsable(key)
            val reason = when {
                m == null -> "removed"
                m.optBoolean("hidden", false) -> "hidden"
                !m.optBoolean("enabled", true) -> "disabled"
                m.optBoolean("paid", false) -> "paid"
                Store.providerByName(m.optString("provider"))?.optBoolean("enabled", true) == false -> "provider off"
                m.has("lastOk") && !m.optBoolean("lastOk") -> "fail"
                else -> ""
            }
            val t = TextView(this)
            t.text = if (m != null) m.optString("id") else key
            t.textSize = 14.5f
            t.setTextColor(if (usable) TXT else DIM)
            t.maxLines = 1
            r.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (reason.isNotBlank()) {
                val st = TextView(this)
                st.text = reason
                st.textSize = 12f
                st.setTextColor(if (reason == "fail") RED else DIM)
                st.setPadding(dp(8), 0, dp(4), 0)
                r.addView(st)
            }
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
            if (!readOnly) r.addView(up)
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
            if (!readOnly) r.addView(down)
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
            if (!readOnly) r.addView(x)
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
        // Auto-managed combos — no Add / Sort / Rename / Delete;
        // they rebuild themselves from the models.
        if (Store.isAutoManaged(name)) {
            val msg = if (Store.comboByName(name)?.optBoolean("autoBest", false) == true)
                "Auto-managed auto-best combo — models that replied successfully join automatically"
            else
                "Auto-managed alias combo — same model from all providers, rebuilt automatically"
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            return
        }
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
        // Hidden models stay hidden here too: only usable models are
        // listed — a model that is disabled itself, belongs to a
        // disabled (hidden) provider, or failed its last test is left
        // out. Same definition as Hide failed (isUnusable), so what
        // you hide in the Models tab never sneaks into a combo.
        val all = sortedModelKeys().filter { k ->
            val m = Store.findModel(k)
            m != null && !isUnusable(m)
        }
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
        list.isFastScrollEnabled = true
        list.isFastScrollAlwaysVisible = true
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
                    // Two-line row: the model id wraps onto as many lines
                    // as it needs (it used to be a single line with no
                    // ellipsis, so long ids were hard-clipped at the right
                    // edge); provider name underneath in small dim text.
                    val inner = LinearLayout(this@MainActivity)
                    inner.orientation = LinearLayout.VERTICAL
                    l.addView(inner, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    val t = TextView(this@MainActivity)
                    t.textSize = 14.5f
                    t.setTextColor(TXT)
                    inner.addView(t)
                    val pv = TextView(this@MainActivity)
                    pv.textSize = 11.5f
                    pv.setTextColor(DIM)
                    inner.addView(pv)
                    l
                }
                val m = modelIndex[key]
                val dot = c.getChildAt(0) as TextView
                dot.text = if (checked[key] == true) "\u25C9" else "\u25CB"
                dot.setTextColor(if (checked[key] == true) ACCENT else DIM)
                val inner = c.getChildAt(1) as LinearLayout
                val t = inner.getChildAt(0) as TextView
                t.text = if (m != null) m.optString("id") else key
                val pv = inner.getChildAt(1) as TextView
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

    // Long-press menu for providers — kept for later, but unused for
    // now: long-press is disabled and every action lives in the
    // expanded provider card instead.
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

    // Long-press on any model. One rule for single vs multi:
    // "Activate" here activates this one model immediately — no select
    // mode needed. "Select" opens select mode with this model already
    // ticked, for picking several models (Activate N / Test sel).
    // "Hide this model" hides just this model (it stays out of the
    // list, combos and /v1/models until shown again); the global
    // Hide-failed toggle lives only in the bottom button row, not here.
    private fun modelLongPress(key: String) {
        val m = Store.findModel(key) ?: return
        val enabled = m.optBoolean("enabled", true)
        val hidden = m.optBoolean("hidden", false)
        val opts = when {
            hidden -> arrayOf("Select", "Show this model", "Test this model")
            !enabled -> arrayOf("Select", "Re-enable", "Test this model", "Hide this model")
            else -> arrayOf("Activate", "Select", "Add to combo", "Test this model", "Hide this model")
        }
        AlertDialog.Builder(this)
            .setTitle(m.optString("id") + " (" + m.optString("provider") + ")")
            .setItems(opts) { _, w ->
                when (opts[w]) {
                    "Activate" -> Store.setActiveSingle(m.optString("provider"), m.optString("id"))
                    "Select" -> { selectMode = true; selected.clear(); selected.add(key) }
                    "Add to combo" -> chooseComboFor(key)
                    "Test this model" -> testBatch(arrayListOf(key))
                    "Hide this model" -> { m.put("hidden", true); Store.save() }
                    "Show this model" -> { m.put("hidden", false); Store.save() }
                    "Re-enable" -> { m.put("enabled", true); m.put("failStreak", 0); Store.save() }
                }
                refresh()
            }
            .show()
    }

    private fun chooseComboFor(key: String) {
        val cs = Store.combos()
        val names = ArrayList<String>()
        for (i in 0 until cs.length()) {
            // Auto-managed combos — never hand-add to them.
            if (Store.isAutoManaged(cs.getJSONObject(i).optString("name"))) continue
            names.add(cs.getJSONObject(i).optString("name"))
        }
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
                            if (Store.addCombo(n)) {
                                if (Store.comboAddModel(n, key)) { expandedCombo = n; refresh() }
                                else Toast.makeText(this, "Model is hidden or unusable — cannot add", Toast.LENGTH_SHORT).show()
                            }
                            else Toast.makeText(this, "Name taken or empty", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else {
                    if (Store.comboKeys(names[w]).contains(key)) {
                        Toast.makeText(this, "Already in " + names[w], Toast.LENGTH_SHORT).show()
                    } else if (Store.comboAddModel(names[w], key)) {
                        Toast.makeText(this, "Added to " + names[w], Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Model is hidden or unusable — cannot add", Toast.LENGTH_SHORT).show()
                    }
                    refresh()
                }
            }
            .show()
    }

    private fun activateSelected() {
        if (selected.isEmpty()) { Toast.makeText(this, "Nothing selected", Toast.LENGTH_SHORT).show(); return }
        if (selected.size == 1) {
            val key = selected.iterator().next()
            if (!Store.isModelUsable(key)) {
                Toast.makeText(this, "Model is hidden or unusable — cannot activate", Toast.LENGTH_SHORT).show()
                return
            }
            val parts = key.split("|")
            if (parts.size >= 2) Store.setActiveSingle(parts[0], key.substringAfter("|"))
            selectMode = false
            selected.clear()
            refresh()
            return
        }
        // Only usable models go into the new combo — hidden provider
        // models (or failed/disabled ones) picked up while Hide failed
        // was off are left out, with a note instead of silently
        // creating a combo that can't run.
        val keys = ArrayList(selected.filter { Store.isModelUsable(it) })
        if (keys.isEmpty()) {
            Toast.makeText(this, "No usable models selected", Toast.LENGTH_SHORT).show()
            return
        }
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
            var addedKeys = emptyList<String>()
            var err = ""
            try {
                val list = ProviderClient.listModels(p)
                val r = Store.refreshProviderModels(name, list)
                added = r.first
                removed = r.second
                addedKeys = r.third
            } catch (e: Exception) { err = (e.message ?: e.toString()).take(80) }
            val a = added
            val rm = removed
            val nk = addedKeys
            val e2 = err
            runOnUiThread {
                if (e2.isNotBlank()) Toast.makeText(this, "Refresh failed: " + e2, Toast.LENGTH_LONG).show()
                else {
                    Toast.makeText(this, "+" + a + " new, -" + rm + " removed", Toast.LENGTH_LONG).show()
                    // A refresh replaces the pending set: only these
                    // new models still need testing.
                    pendingNewTestKeys = ArrayList(nk)
                }
                refresh()
                maybeAutoTestNew()
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
        if (testing || fetching) { Toast.makeText(this, "Already running, please wait", Toast.LENGTH_SHORT).show(); return }
        fetching = true
        Toast.makeText(this, "Fetching models\u2026", Toast.LENGTH_SHORT).show()
        Thread {
            val providers = ArrayList<JSONObject>()
            val ps = Store.providers()
            for (i in 0 until ps.length()) {
                val p = ps.getJSONObject(i)
                if (p.optBoolean("enabled", true)) providers.add(p)
            }
            val totalProviders = providers.size
            runOnUiThread { showProgress("Fetching", totalProviders) }
            val added = java.util.concurrent.atomic.AtomicInteger(0)
            val removed = java.util.concurrent.atomic.AtomicInteger(0)
            val failed = java.util.concurrent.atomic.AtomicInteger(0)
            val newKeys = java.util.Collections.synchronizedList(ArrayList<String>())
            val doneProviders = java.util.concurrent.atomic.AtomicInteger(0)
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
                        newKeys.addAll(r.third)
                    } catch (e: Exception) { failed.incrementAndGet() }
                    finally {
                        val d = doneProviders.incrementAndGet()
                        runOnUiThread { updateProgress("Fetching", d, totalProviders) }
                    }
                }
            }
            pool.shutdown()
            pool.awaitTermination(10, java.util.concurrent.TimeUnit.MINUTES)
            val a = added.get()
            val rm = removed.get()
            val f = failed.get()
            val nk = ArrayList(newKeys)
            fetching = false
            runOnUiThread {
                hideProgress()
                Toast.makeText(this, "+" + a + " new, -" + rm + " removed, " + f + " providers failed",
                    Toast.LENGTH_LONG).show()
                // A fetch replaces the pending set: only these new
                // models still need testing.
                pendingNewTestKeys = nk
                refresh()
                maybeAutoTestNew()
            }
        }.start()
    }

    // A probe result. `permanent` means retrying will not help
    // (account/model-gone errors, or a provider rejection on a 200),
    // so Test All can skip this model next time.
    private data class ProbeResult(val ok: Boolean, val latencyMs: Long, val err: String, val permanent: Boolean)

    private fun chatProbe(m: JSONObject): ProbeResult {
        val p = Store.providerByName(m.optString("provider")) ?: return ProbeResult(false, 0L, "no provider", true)
        val t0 = System.currentTimeMillis()
        val body = JSONObject().put("model", m.optString("id"))
            .put("messages", JSONArray().put(JSONObject()
                .put("role", "user").put("content", "hi")))
            .put("max_tokens", 1)
        var ok = false
        var err = ""
        var permanent = false
        try {
            val r = ProviderClient.chat(p, m.optString("id"), body, false, 60000)
            val raw = String(r.body ?: ByteArray(0), Charsets.UTF_8)
            // "Pass" means: this key can actually use this model.
            // A bare 200 is not enough — some relays answer 200 with an
            // error object, or an empty reply, for models the key isn't
            // entitled to (e.g. paid models on a free key). Those must
            // fail, or RikkaHub will pick them later and fail instead.
            when {
                r.status == 429 -> {
                    // Rate-limited proves the key IS entitled to this
                    // model — the provider recognised it and throttled
                    // it instead of rejecting it. So it passes, but the
                    // note is kept so the user knows it was throttled.
                    ok = true
                    err = "rate limited (429)"
                    permanent = false
                }
                r.status !in 200..299 -> {
                    ok = false
                    // Account or model-gone errors are permanent:
                    // retesting the same key/model will fail the same
                    // way. Anything else (5xx, timeouts) is transient.
                    permanent = r.status in setOf(400, 401, 402, 403, 404)
                    err = raw.take(120)
                }
                else -> {
                    val j = try { JSONObject(raw) } catch (e: Exception) { null }
                    // opt() returns JSONObject.NULL for an explicit null,
                    // which is not a real error — only a present value is.
                    val topErr = j?.opt("error")?.takeUnless { it === JSONObject.NULL }
                    val content = j?.optJSONArray("choices")?.optJSONObject(0)
                        ?.optJSONObject("message")?.optString("content", "") ?: ""
                    when {
                        topErr != null -> { ok = false; permanent = true; err = topErr.toString().take(120) }
                        content.isBlank() -> { ok = false; permanent = false; err = "empty reply" }
                        else -> ok = true
                    }
                }
            }
            r.close()
        } catch (e: Exception) { err = (e.message ?: e.toString()).take(120) }
        return ProbeResult(ok, System.currentTimeMillis() - t0, err, permanent)
    }

    private fun testBatch(keysIn: List<String>) {
        if (keysIn.isEmpty()) { Toast.makeText(this, "Nothing selected", Toast.LENGTH_SHORT).show(); return }
        // Re-entry guard: a second tap while a batch is running used to
        // start a second pool on the same models.
        if (testing || fetching) { Toast.makeText(this, "Already running, please wait", Toast.LENGTH_SHORT).show(); return }
        testing = true
        val total = keysIn.size
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        runOnUiThread { showProgress("Testing", total) }
        Toast.makeText(this, "Testing " + total + " models\u2026", Toast.LENGTH_SHORT).show()
        Thread {
            val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
            for (k in keysIn) {
                pool.submit {
                    try {
                        val m = Store.findModel(k) ?: return@submit
                        // Paid models are never probed: they fail even
                        // when they would have replied well (they cost
                        // money this key shouldn't spend), so no request
                        // is sent at all — just mark failed instantly.
                        if (m.optBoolean("paid", false)) {
                            try {
                                Store.bumpModel(k, false, 0L, "paid model", permanentFail = true)
                                Store.addLog(LogEntry(System.currentTimeMillis(), m.optString("provider"),
                                    m.optString("id"), 0, 0L, "paid model"))
                            } catch (ignored: Exception) {}
                        } else {
                            val r = chatProbe(m)
                            // Apply each result as it finishes (instead of
                            // only after the whole batch): the list updates
                            // live, and one slow model can't hold the rest
                            // hostage. bumpModel/addLog only touch this
                            // model's own object plus synchronized save/log.
                            try {
                                Store.bumpModel(k, r.ok, r.latencyMs, r.err, permanentFail = r.permanent)
                                Store.addLog(LogEntry(System.currentTimeMillis(), m.optString("provider"),
                                    m.optString("id"), if (r.ok) 200 else 0, r.latencyMs, r.err))
                            } catch (ignored: Exception) {}
                        }
                    } finally {
                        val d = done.incrementAndGet()
                        runOnUiThread {
                            updateProgress("Testing", d, total)
                            if (d % 4 == 0 || d == total) {
                                // Rebuild the visible keys, not just
                                // re-render: with Hide failed on, a model
                                // that just failed must vanish right away
                                // instead of waiting for the batch to end.
                                rebuildModelKeys()
                                (modelsList.adapter as BaseAdapter).notifyDataSetChanged()
                            }
                        }
                    }
                }
            }
            pool.shutdown()
            // The old code ignored awaitTermination's return value: when
            // it timed out, the toast still claimed every model was
            // tested. Now a timeout stops the pool and says so.
            val finished = pool.awaitTermination(15, java.util.concurrent.TimeUnit.MINUTES)
            if (!finished) pool.shutdownNow()
            testing = false
            val d = done.get()
            runOnUiThread {
                hideProgress()
                Toast.makeText(this,
                    if (finished) "" + d + " models tested"
                    else "" + d + " of " + total + " tested (timed out)",
                    Toast.LENGTH_LONG).show()
                refresh()
            }
        }.start()
    }

    // Smart retest decision for Test All, in order:
    // (a) paid model already marked permanently failed -> never again;
    //     paid but not yet marked -> true, so the batch instant-fails it.
    // (b) never tested -> always. (c) passed -> only when the last test
    //     is older than PASS_FRESH_MS (24h). (d) failed -> only transient
    //     failures (429/5xx/timeout) get another chance; permanent
    //     401/402/403/404 fails never auto-retest.
    private fun shouldRetest(m: JSONObject): Boolean {
        if (m.optBoolean("paid", false)) return !m.optBoolean("lastFailPermanent", false)
        if (!m.has("lastOk")) return true
        if (m.optBoolean("lastOk")) {
            val ts = m.optLong("lastTestTs", 0L)
            return System.currentTimeMillis() - ts > PASS_FRESH_MS
        }
        return !m.optBoolean("lastFailPermanent", false)
    }

    private fun testAll() {
        // Test only what needs testing, not every stored model:
        // re-probing fresh passes and permanently failed models every
        // time wasted most of the batch. Test All covers everything,
        // so it also clears any pending new-models set.
        val toTest = ArrayList<String>()
        var skipped = 0
        val ms = Store.models()
        for (i in 0 until ms.length()) {
            val m = ms.getJSONObject(i)
            val key = m.optString("provider") + "|" + m.optString("id")
            if (shouldRetest(m)) toTest.add(key) else skipped++
        }
        pendingNewTestKeys.clear()
        if (toTest.isEmpty()) {
            Toast.makeText(this, "Nothing to test — all fresh or permanently failed", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Testing " + toTest.size + " models — " + skipped +
            " skipped (fresh passes / permanent fails)", Toast.LENGTH_LONG).show()
        testBatch(toTest)
    }

    // Request log with a filter box, a "Failed only" toggle and a
    // token header (grand total + the 5 heaviest models by usedTokens).
    // Filter state is kept across opens, so reopening the log keeps
    // the same view.
    private fun logDialog() {
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        val pad = dp(16)
        wrap.setPadding(pad, dp(8), pad, 0)
        val head = TextView(this)
        head.textSize = 13f
        head.setTextColor(DIM)
        val scored = ArrayList<Pair<String, Long>>()
        var totalTok = 0L
        val ms = Store.models()
        for (i in 0 until ms.length()) {
            val m = ms.getJSONObject(i)
            val t = m.optLong("usedTokens", 0L)
            if (t > 0) {
                totalTok += t
                scored.add(Pair(m.optString("id"), t))
            }
        }
        scored.sortByDescending { it.second }
        val top = scored.take(5).joinToString("\n") { "  " + it.first + ": " + it.second + " tok" }
        head.text = "Total tokens: " + totalTok + (if (top.isBlank()) "" else "\n" + top)
        head.setPadding(0, 0, 0, dp(8))
        wrap.addView(head)
        val filterBox = EditText(this)
        filterBox.hint = "filter: provider / model / error"
        filterBox.setText(logFilterText)
        wrap.addView(filterBox)
        val failedBtn = textBtn(if (logFailedOnly) "Failed only: ON" else "Failed only: OFF") {}
        failedBtn.setPadding(0, dp(8), 0, dp(4))
        wrap.addView(failedBtn)
        val scroll = ScrollView(this)
        val body = TextView(this)
        body.textSize = 12.5f
        body.setTextColor(TXT)
        scroll.addView(body)
        wrap.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)))
        fun render() {
            val sb = StringBuilder()
            val q = logFilterText.lowercase()
            val logs = synchronized(Store.log) { Store.log.takeLast(60).reversed() }
            var shown = 0
            for (e in logs) {
                val isFail = e.status !in 200..299
                if (logFailedOnly && !isFail) continue
                val line = e.model + "  " + e.status + "  " + e.latencyMs + "ms" +
                    (if (e.tokens > 0) "  " + e.tokens + " tok" else "") +
                    (if (e.error.isNotBlank()) "\n   " + e.error.take(90) else "")
                if (q.isNotBlank() && !(e.provider.lowercase().contains(q) ||
                    e.model.lowercase().contains(q) || e.error.lowercase().contains(q))) continue
                val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(e.ts))
                sb.append(t).append("  ").append(line).append("\n")
                shown++
            }
            body.text = if (shown == 0) "no matching requests" else sb.toString()
        }
        filterBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { logFilterText = s?.toString() ?: ""; render() }
        })
        failedBtn.setOnClickListener {
            logFailedOnly = !logFailedOnly
            failedBtn.text = if (logFailedOnly) "Failed only: ON" else "Failed only: OFF"
            render()
        }
        render()
        // Copy copies exactly what is on screen right now (including
        // the active filter / Failed-only view), same clipboard pattern
        // as the crash-log Copy button.
        AlertDialog.Builder(this).setTitle("Request log (last 60)")
            .setView(wrap)
            .setNeutralButton("Copy") { _, _ ->
                val txt = body.text.toString()
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("LLM Router request log", txt))
                Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("OK", null).show()
    }

    private fun aboutDialog() {
        val msg = "LLM Router v0.5.9\n\nCreator: Maynul Haque\n\nsmall \u00b7 offline \u00b7 no tracking"
        AlertDialog.Builder(this).setTitle("About").setMessage(msg)
            .setPositiveButton("OK", null).show()
    }

    private fun serverDialog() {
        val ps = Store.providers()
        val msg = "Server: " + (if (HttpServerHolder.running) "ON" else "OFF") + "\n" +
            "Base URL: http://127.0.0.1:" + Store.cfg.optInt("port", 8790) + "/v1\n" +
            "API key: any value\n\n" +
            ps.length() + " providers \u00b7 " + Store.models().length() + " models \u00b7 " +
            Store.combos().length() + " combos"
        AlertDialog.Builder(this).setTitle("Server info").setMessage(msg)
            .setPositiveButton("OK", null).show()
    }

    private fun howToDialog() {
        val msg = "1. Add a provider: Providers tab \u2192 + Add \u2192 name, base URL, API key.\n\n" +
            "2. Fetch models: Providers tab \u2192 Fetch all.\n\n" +
            "3. Test: Models tab \u2192 Test all. Only models that actually reply pass.\n\n" +
            "4. Pick a model: successful models join the auto-best combo by themselves (fastest first), or make your own combo in the Combos tab and Activate it.\n\n" +
            "5. Start the server: tap the Server pill (or the drawer Server toggle) so it shows ON.\n\n" +
            "6. In any OpenAI-compatible app: add a custom provider \u2014 Base URL http://127.0.0.1:" + Store.cfg.optInt("port", 8790) + "/v1, API key any value, model auto-best (or a combo name, or one model id).\n\n" +
            "7. If a model fails or its quota runs out, the router falls back to the next one automatically. The drawer Request log shows what was tried."
        AlertDialog.Builder(this).setTitle("How to use").setMessage(msg)
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
