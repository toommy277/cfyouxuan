package com.sidewang.cfoptimizer

import android.app.Activity
import android.graphics.Color
import android.app.AlertDialog
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.JavascriptInterface
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var api: LocalApiServer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep Android system bars visually continuous with the app's dark background.
        window.statusBarColor = Color.parseColor("#0f172a")
        window.navigationBarColor = Color.parseColor("#0f172a")
        window.decorView.systemUiVisibility = 0
        api = LocalApiServer(this)
        try { api.start() } catch (e: Exception) {
            Toast.makeText(this, "本地服务启动失败：${e.message}", Toast.LENGTH_LONG).show()
        }
        webView = WebView(this)
        webView.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(object {
            @JavascriptInterface fun uploadConfig() { runOnUiThread { showUploadConfig() } }
            @JavascriptInterface fun reloadPage() { runOnUiThread { webView.reload() } }
        }, "AndroidApp")
        webView.webViewClient = object : WebViewClient() {}
        setContentView(webView)
        webView.loadUrl("http://127.0.0.1:8788/")
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "上传配置")
        menu.add(0, 2, 1, "重新加载")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> { showUploadConfig(); return true }
            2 -> { webView.reload(); return true }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showUploadConfig() {
        val prefs = getSharedPreferences("worker_config", MODE_PRIVATE)
        val url = EditText(this).apply {
            hint = "Worker 地址，例如 https://example.workers.dev"
            setSingleLine(true)
            setText(prefs.getString("workerUrl", "") ?: "")
        }
        val secret = EditText(this).apply {
            hint = "Worker Secret"
            setSingleLine(true)
            setText(prefs.getString("workerSecret", "") ?: "")
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(url)
            addView(secret)
        }
        AlertDialog.Builder(this)
            .setTitle("上传配置")
            .setMessage("配置仅保存在本机 App 私有目录。")
            .setView(layout)
            .setNegativeButton("取消", null)
            .setNeutralButton("清除") { _, _ ->
                prefs.edit().clear().apply()
                Toast.makeText(this, "上传配置已清除", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("保存") { _, _ ->
                prefs.edit().putString("workerUrl", url.text.toString().trim().trimEnd('/'))
                    .putString("workerSecret", secret.text.toString()).apply()
                Toast.makeText(this, "上传配置已保存", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    override fun onDestroy() {
        try { api.stop() } catch (_: Exception) {}
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }
}
