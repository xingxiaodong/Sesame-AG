package fansirsqi.xposed.sesame.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.addCallback
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import fansirsqi.xposed.sesame.R
import fansirsqi.xposed.sesame.databinding.ActivityHtmlViewerBinding
import fansirsqi.xposed.sesame.util.Files
import fansirsqi.xposed.sesame.util.LanguageUtil
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.ToastUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

class HtmlViewerActivity : BaseActivity() {

    // 推荐使用 ViewBinding，需要在 build.gradle 开启
    private lateinit var binding: ActivityHtmlViewerBinding

    private var uri: Uri? = null
    private var canClear: Boolean = false

    // 倒排索引：使用并发容器更安全
    private val searchIndex = ConcurrentHashMap<String, MutableList<Int>>()

    // 保存所有日志行
    private var allLogLines: List<String> = emptyList()
    private var currentLoadedCount = 0
    private var dynamicBatchSize = LOAD_MORE_LINES

    // 预编译正则，避免循环中重复编译 (极大提升性能)
    private val regexEnglish = Regex("[a-zA-Z]{2,}")
    private val regexChinese = Regex("[\\u4e00-\\u9fa5]+")
    private val regexNumber = Regex("\\d{3,}")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LanguageUtil.setLocale(this)
        // 初始化 ViewBinding
        binding = ActivityHtmlViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        handleInsets()

        // 注册返回键回调 (替代 onBackPressed)
        onBackPressedDispatcher.addCallback(this) {
            if (binding.mwvWebview.canGoBack()) {
                binding.mwvWebview.goBack()
            } else {
                finish()
            }
        }
    }

    private fun setupUI() {
        val colorSelection = ContextCompat.getColor(this, R.color.selection_color)
        binding.pgbWebview.progressTintList = ColorStateList.valueOf(colorSelection)
        binding.mwvWebview.setBackgroundColor(ContextCompat.getColor(this, R.color.background))

        setupWebView()
    }

    private fun handleInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.mwvWebview.setPadding(
                binding.mwvWebview.paddingLeft,
                binding.mwvWebview.paddingTop,
                binding.mwvWebview.paddingRight,
                systemBars.bottom
            )
            insets
        }
    }

    private fun setupWebView() {
        binding.mwvWebview.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, progress: Int) {
                binding.pgbWebview.progress = progress
                if (progress < 100) {
                    baseSubtitle = "Loading..."
                    binding.pgbWebview.visibility = View.VISIBLE
                } else {
                    // 如果正在加载 log 文件，显示文件名，否则显示标题
                    baseSubtitle = if (uri?.scheme == "file" && uri?.path?.endsWith(".log") == true) {
                        uri?.lastPathSegment
                    } else {
                        view?.title
                    }
                    binding.pgbWebview.visibility = View.GONE
                }
            }
        }

        // 基础设置
        binding.mwvWebview.settings.apply {
            // 安全性设置：默认先禁用 JS，根据内容再开启
            javaScriptEnabled = false
            domStorageEnabled = false
            defaultTextEncodingName = "utf-8"
        }

        // 夜间模式适配
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            try {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(binding.mwvWebview.settings, true)
            } catch (e: Exception) {
                Log.printStackTrace(TAG, "设置夜间模式失败", e)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onResume() {
        super.onResume()

        val intent = intent ?: return
        val settings = binding.mwvWebview.settings

        // 配置 WebSettings
        settings.apply {
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            textZoom = 85

            // 根据 intent 配置
            useWideViewPort = !intent.getBooleanExtra("nextLine", true)
        }

        uri = intent.data
        canClear = intent.getBooleanExtra("canClear", false)

        uri?.let { validUri ->
            // 日志浏览模式配置
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true

            // 添加 JS 接口
            binding.mwvWebview.removeJavascriptInterface("SearchBridge") // 避免重复添加
            binding.mwvWebview.addJavascriptInterface(SearchBridge(), "SearchBridge")

            // 加载本地 HTML 模板
            binding.mwvWebview.loadUrl("file:///android_asset/log_viewer.html")

            // 更新 Client 监听加载完成
            binding.mwvWebview.webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, progress: Int) {
                    binding.pgbWebview.progress = progress
                    binding.pgbWebview.visibility = if (progress < 100) View.VISIBLE else View.GONE

                    if (progress == 100) {
                        baseSubtitle = validUri.lastPathSegment
                        // 页面加载完成后，开始读取日志数据
                        if (validUri.scheme.equals("file", ignoreCase = true) && validUri.path?.endsWith(".log") == true) {
                            validUri.path?.let { loadLogWithFlow(it) }
                        }
                    }
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 1, getString(R.string.export_file))
        if (canClear) {
            menu.add(0, 2, 2, getString(R.string.clear_file))
        }
        menu.add(0, 3, 3, getString(R.string.open_with_other_browser))
        menu.add(0, 4, 4, getString(R.string.copy_the_url))
        menu.add(0, 5, 5, getString(R.string.scroll_to_top))
        menu.add(0, 6, 6, getString(R.string.scroll_to_bottom))
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> exportFile()
            2 -> clearFile()
            3 -> openWithBrowser()
            4 -> copyUrlToClipboard()
            5 -> binding.mwvWebview.evaluateJavascript("if (typeof loadAllAndScrollToTop === 'function') { loadAllAndScrollToTop(); } else { window.scrollTo(0, 0); }", null)
            6 -> binding.mwvWebview.scrollToBottom()
        }
        return true
    }

    private fun exportFile() {
        val path = uri?.path ?: return
        try {
            val file = File(path)
            if (!file.exists()) {
                ToastUtil.showToast("源文件不存在")
                return
            }
            val exportFile = Files.exportFile(file, true)
            if (exportFile != null && exportFile.exists()) {
                ToastUtil.showToast("${getString(R.string.file_exported)} ${exportFile.path}")
            } else {
                ToastUtil.showToast("导出失败")
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "Export error", e)
            ToastUtil.showToast("导出异常: ${e.message}")
        }
    }

    private fun clearFile() {
        val path = uri?.path ?: return
        try {
            if (Files.clearFile(File(path))) {
                ToastUtil.showToast("文件已清空")
                binding.mwvWebview.reload()
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "Clear error", e)
        }
    }

    private fun openWithBrowser() {
        uri?.let {
            if (it.scheme?.startsWith("http") == true) {
                startActivity(Intent(Intent.ACTION_VIEW, it))
            } else {
                ToastUtil.showToast("不支持使用浏览器打开本地文件")
            }
        }
    }

    private fun copyUrlToClipboard() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("url", binding.mwvWebview.url))
        ToastUtil.showToast(getString(R.string.copy_success))
    }

    override fun onDestroy() {
        // 先移除监听
        if (binding.mwvWebview is MyWebView) {
            (binding.mwvWebview as MyWebView).stopWatchingIncremental()
        }
        // 清理 WebView
        binding.mwvWebview.apply {
            loadUrl("about:blank")
            stopLoading()
            webChromeClient = null
            destroy()
        }
        super.onDestroy()
    }

    // ================== 核心业务逻辑 ==================

    private fun loadLogWithFlow(path: String) {
        lifecycleScope.launch {
            try {
                searchIndex.clear()
                currentLoadedCount = 0

                // 1. IO线程读取文件
                val (totalLines, lastLines) = withContext(Dispatchers.IO) {
                    getLastLines(path)
                }

                allLogLines = lastLines

                // 2. 准备 UI 数据
                val header = buildHeaderInfo(totalLines)
                binding.mwvWebview.evaluateJavascript("setFullText(${JSONObject.quote(header)})", null)

                // 3. 初始加载 (Flow)
                val initialLoadCount = min(200, allLogLines.size)
                val initialLines = allLogLines.takeLast(initialLoadCount)
                currentLoadedCount = initialLines.size

                loadLinesFlow(initialLines).collect { batch ->
                    // 原生 JSONArray 极快
                    val json = JSONArray(batch).toString()
                    binding.mwvWebview.evaluateJavascript("appendLines($json)", null)
                }

                // 4. 通知前端加载完成
                val hasMore = currentLoadedCount < allLogLines.size
                binding.mwvWebview.evaluateJavascript(
                    "if(typeof onInitialLoadComplete === 'function') { onInitialLoadComplete(0, $currentLoadedCount, ${allLogLines.size}, $hasMore); }",
                    null
                )

                // 5. 开启文件变化监听
                binding.mwvWebview.startWatchingIncremental(path)

                // 6. 异步构建索引
                launch(Dispatchers.Default) { // CPU 密集型任务使用 Default
                    buildSearchIndex(allLogLines)
                    withContext(Dispatchers.Main) {
                        binding.mwvWebview.evaluateJavascript(
                            "if(typeof onIndexBuilt === 'function') onIndexBuilt(${searchIndex.size})",
                            null
                        )
                    }
                }

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.printStackTrace(TAG, "Load log failed", e)
            }
        }
    }

    private fun buildHeaderInfo(totalLines: Int): String {
        return if (totalLines > MAX_DISPLAY_LINES) {
            """
            === 日志文件较大，加载最后 $MAX_DISPLAY_LINES 行 ===
            === 总计 $totalLines 行，已跳过前 ${totalLines - MAX_DISPLAY_LINES} 行 ===
            === ⚡ 末尾读取 + 智能懒加载技术 ===
            
            """.trimIndent()
        } else {
            """
            === 📄 总计 $totalLines 行日志 ===
            === 📱 智能加载，往上滑动自动加载更多 ===
            
            """.trimIndent()
        }
    }

    private fun loadLinesFlow(lines: List<String>): Flow<List<String>> = flow {
        lines.chunked(BATCH_SIZE).forEach { emit(it) }
    }.flowOn(Dispatchers.Default) // JSON转换可能是CPU密集型，用Default

    /**
     * 构建索引 (CPU 密集型)
     */
    private fun buildSearchIndex(lines: List<String>) {
        lines.forEachIndexed { lineIndex, line ->
            if (line.isBlank()) return@forEachIndexed
            extractKeywords(line).forEach { keyword ->
                // 使用 ConcurrentHashMap 的 computeIfAbsent 确保线程安全
                searchIndex.computeIfAbsent(keyword) { Collections.synchronizedList(ArrayList()) }
                    .add(lineIndex)
            }
        }
    }

    /**
     * 提取关键词 (使用预编译正则优化性能)
     */
    private fun extractKeywords(line: String): Set<String> {
        val keywords = HashSet<String>()

        // 1. 英文
        regexEnglish.findAll(line).forEach { keywords.add(it.value.lowercase()) }

        // 2. 数字
        regexNumber.findAll(line).forEach { keywords.add(it.value) }

        // 3. 中文 (滑动窗口逻辑)
        regexChinese.findAll(line).forEach { match ->
            val text = match.value
            val len = text.length
            if (len < 2) return@forEach

            val maxWordLen = min(4, len)
            for (l in 2..maxWordLen) {
                for (i in 0..len - l) {
                    keywords.add(text.substring(i, i + l))
                }
            }
        }
        return keywords
    }

    // ================== JS Bridge ==================

    inner class SearchBridge {
        @JavascriptInterface
        fun search(keyword: String?): String {
            if (keyword.isNullOrBlank()) return "[]"
            val key = keyword.lowercase()
            // 优先精确匹配，然后小写匹配
            val list = searchIndex[keyword] ?: searchIndex[key] ?: return "[]"
            // 复制一份返回，防止并发修改异常
            return JSONArray(list).toString()
        }

        @JavascriptInterface
        fun searchLines(keyword: String?): String {
            if (keyword.isNullOrBlank()) return """{"lines": [], "total": 0}"""

            return try {
                val key = keyword.lowercase()
                val indices = searchIndex[keyword] ?: searchIndex[key]

                val matchedLines = if (indices != null) {
                    indices.mapNotNull { allLogLines.getOrNull(it) }
                } else {
                    // 索引没命中，降级为全文搜索
                    allLogLines.filter { it.contains(keyword, true) }
                }

                val json = JSONObject().apply {
                    put("lines", JSONArray(matchedLines))
                    put("total", matchedLines.size)
                    put("source", if (indices != null) "index" else "fulltext")
                }
                json.toString()
            } catch (e: Exception) {
                """{"lines": [], "total": 0, "error": "${e.message}"}"""
            }
        }

        @JavascriptInterface
        fun getIndexStats(): String {
            return """{"keywords": ${searchIndex.size}, "lines": ${allLogLines.size}}"""
        }

        @JavascriptInterface
        fun setLoadBatchSize(size: Int) {
            if (size > 0) dynamicBatchSize = size
        }

        @JavascriptInterface
        fun loadMore(count: Int): String {
            val remaining = allLogLines.size - currentLoadedCount
            if (remaining <= 0) return "[]"

            val actualCount = min(count, remaining)
            val start = allLogLines.size - currentLoadedCount - actualCount
            val end = allLogLines.size - currentLoadedCount

            val lines = allLogLines.subList(start, end)
            currentLoadedCount += lines.size

            return JSONArray(lines).toString()
        }

        @JavascriptInterface
        fun hasMore(): Boolean = currentLoadedCount < allLogLines.size
    }

    companion object {
        private val TAG = HtmlViewerActivity::class.java.simpleName
        private const val LOAD_MORE_LINES = 500
        private const val MAX_DISPLAY_LINES = 200000
        private const val BATCH_SIZE = 50

        /**
         * 优化版：使用 useLines 处理文件流，更符合 Kotlin 惯用语法
         */
        private fun getLastLines(path: String): Pair<Int, List<String>> {
            val file = File(path)
            if (!file.exists() || file.length() == 0L) return 0 to emptyList()

            val buffer = ArrayDeque<String>(MAX_DISPLAY_LINES)
            var totalLines = 0

            // useLines 会自动关闭流，且处理大文件更高效
            file.useLines { sequence ->
                sequence.forEach { line ->
                    totalLines++
                    buffer.addLast(line)
                    if (buffer.size > MAX_DISPLAY_LINES) {
                        buffer.removeFirst()
                    }
                }
            }
            return totalLines to buffer.toList()
        }
    }
}