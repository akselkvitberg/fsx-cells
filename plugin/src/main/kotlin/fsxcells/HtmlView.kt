package fsxcells

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import javax.swing.JComponent

/**
 * An embedded browser (JCEF, off-screen rendering) showing one output page. It reports the page's content height
 * so the block can size itself, and hands wheel scrolling back to the editor when the page can't scroll further.
 */
class HtmlView(
    page: String,
    private val onContentHeight: (Int) -> Unit,
    private val onWheelOverflow: (Double) -> Unit,
) : Disposable {

    private val browser: JBCefBrowser = JBCefBrowser.createBuilder()
        .setOffScreenRendering(true)
        .setEnableOpenDevToolsMenuItem(false)
        .build()

    // Queries have to exist before the page loads.
    private val heightQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val wheelQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)

    @Volatile
    private var loaded = false

    @Volatile
    private var dimmed = false

    val component: JComponent get() = browser.component

    init {
        Disposer.register(this, browser)
        Disposer.register(this, heightQuery)
        Disposer.register(this, wheelQuery)

        heightQuery.addHandler { request ->
            request.toDoubleOrNull()?.let { h ->
                ApplicationManager.getApplication().invokeLater({ onContentHeight(Math.ceil(h).toInt()) }) { Disposer.isDisposed(this) }
            }
            null
        }
        wheelQuery.addHandler { request ->
            request.toDoubleOrNull()?.let { d ->
                ApplicationManager.getApplication().invokeLater({ onWheelOverflow(d) }) { Disposer.isDisposed(this) }
            }
            null
        }
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(b: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (!frame.isMain) return
                b.executeJavaScript(pageScript(), b.url, 0)
                loaded = true
                applyDimmed()
            }
        }, browser.cefBrowser)
        browser.loadHTML(page)
    }

    /** Greys the page out (stale or re-running output). */
    fun setDimmed(value: Boolean) {
        dimmed = value
        applyDimmed()
    }

    private fun applyDimmed() {
        if (!loaded) return
        browser.cefBrowser.executeJavaScript(
            "document.documentElement.classList.toggle('fc-dim', $dimmed);", browser.cefBrowser.url, 0,
        )
    }

    private fun pageScript() = """
        (function () {
          function sendHeight(h) { ${heightQuery.inject("h")} }
          function sendWheel(d) { ${wheelQuery.inject("d")} }
          var root = document.getElementById('fc-root');
          var last = -1;
          function report() {
            var h = root.getBoundingClientRect().height;
            if (h !== last) { last = h; sendHeight(String(h)); }
          }
          new ResizeObserver(report).observe(root);
          document.addEventListener('toggle', report, true);
          report();
          window.addEventListener('wheel', function (e) {
            if (e.shiftKey || Math.abs(e.deltaX) > Math.abs(e.deltaY)) return;
            var el = document.scrollingElement;
            var atTop = el.scrollTop <= 0;
            var atBottom = el.scrollTop + el.clientHeight >= el.scrollHeight - 1;
            if ((e.deltaY < 0 && atTop) || (e.deltaY > 0 && atBottom)) {
              e.preventDefault();
              sendWheel(String(e.deltaY));
            }
          }, { passive: false });
        })();
    """.trimIndent()

    override fun dispose() {}

    companion object {
        /** False when the IDE runs without JCEF (some remote/headless setups); output then falls back to text. */
        val isSupported: Boolean get() = try { JBCefApp.isSupported() } catch (_: Throwable) { false }
    }
}
