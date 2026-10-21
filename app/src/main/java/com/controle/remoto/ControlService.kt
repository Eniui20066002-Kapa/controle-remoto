package com.controle.remoto

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Recebe os comandos do PC e executa no celular.
 * Android 7.0+: toques e deslizes reais (dispatchGesture).
 * Android 6.0: clica em elementos clicaveis, rola listas, digita texto e botoes de navegacao.
 */
class ControlService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ControlService? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** Chamado pela thread de rede. Os comandos rodam em ordem, numa fila unica. */
    fun handle(name: String, arg: String) {
        exec.execute {
            try {
                dispatch(name, arg)
            } catch (e: Exception) {
            }
        }
    }

    private fun dispatch(name: String, arg: String) {
        when (name) {
            "BACK" -> performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            "HOME" -> performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            "RECENTS" -> performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            "NOTIF" -> performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            "QS" -> performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            "TAP" -> {
                val f = nums(arg)
                tapAt(f[0], f[1], false)
            }
            "LONG" -> {
                val f = nums(arg)
                tapAt(f[0], f[1], true)
            }
            "SWIPE" -> {
                val f = nums(arg)
                swipe(f[0], f[1], f[2], f[3], f[4].toLong())
            }
            "SCROLL" -> {
                val p = arg.trim().split(" ")
                scroll(p[0].toFloat(), p[1].toFloat(), p[2])
            }
            "TEXT" -> typeText(arg)
            "BKSP" -> backspace()
            "ENTER" -> enter()
        }
    }

    private fun nums(arg: String): List<Float> =
        arg.trim().split(" ").filter { it.isNotEmpty() }.map { it.toFloat() }

    private fun screen(): DisplayMetrics {
        val dm = DisplayMetrics()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm.defaultDisplay.getRealMetrics(dm)
        return dm
    }

    // ------------------------------------------------------------------ toque

    private fun tapAt(nx: Float, ny: Float, longPress: Boolean) {
        val d = screen()
        val x = (nx * d.widthPixels).toInt().coerceIn(0, d.widthPixels - 1)
        val y = (ny * d.heightPixels).toInt().coerceIn(0, d.heightPixels - 1)

        if (Build.VERSION.SDK_INT >= 24) {
            gesture(x.toFloat(), y.toFloat(), x.toFloat(), y.toFloat(), if (longPress) 800L else 40L)
            return
        }

        // Android 6.0: procura o elemento clicavel naquele ponto.
        val action = if (longPress) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        for (root in candidateRoots()) {
            val hit = deepestAt(root, x, y) ?: continue
            var n: AccessibilityNodeInfo? = hit
            while (n != null) {
                val ok = if (longPress) n.isLongClickable else n.isClickable
                if (ok && n.performAction(action)) return
                n = n.parent
            }
        }
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durMs: Long) {
        val d = screen()
        if (Build.VERSION.SDK_INT >= 24) {
            gesture(x1 * d.widthPixels, y1 * d.heightPixels, x2 * d.widthPixels, y2 * d.heightPixels, durMs)
            return
        }
        // Android 6.0: converte o deslize em rolagem.
        val dx = (x2 - x1) * d.widthPixels
        val dy = (y2 - y1) * d.heightPixels
        val dir = if (abs(dy) >= abs(dx)) {
            if (dy < 0) "down" else "up"
        } else {
            if (dx < 0) "right" else "left"
        }
        scroll(x1, y1, dir)
    }

    private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, durMs: Long) {
        val path = Path()
        path.moveTo(x1, y1)
        if (x1 != x2 || y1 != y2) path.lineTo(x2, y2)
        val stroke = GestureDescription.StrokeDescription(path, 0L, durMs.coerceAtLeast(1L))
        val g = GestureDescription.Builder().addStroke(stroke).build()
        main.post {
            try {
                dispatchGesture(g, null, null)
            } catch (e: Exception) {
            }
        }
    }

    // ----------------------------------------------------------------- rolagem

    /** dir: "down" mostra o conteudo de baixo, "up" o de cima, "right"/"left" idem na horizontal. */
    private fun scroll(nx: Float, ny: Float, dir: String) {
        val d = screen()
        if (Build.VERSION.SDK_INT >= 24) {
            val x = nx * d.widthPixels
            val y = ny * d.heightPixels
            val dxy = 0.25f
            when (dir) {
                "down" -> gesture(x, y, x, y - dxy * d.heightPixels, 250L)
                "up" -> gesture(x, y, x, y + dxy * d.heightPixels, 250L)
                "right" -> gesture(x, y, x - dxy * d.widthPixels, y, 250L)
                "left" -> gesture(x, y, x + dxy * d.widthPixels, y, 250L)
            }
            return
        }

        val px = (nx * d.widthPixels).toInt()
        val py = (ny * d.heightPixels).toInt()
        val forward = dir == "down" || dir == "right"
        val specific = when (dir) {
            "down" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
            "up" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
            "right" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
            else -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
        }
        val generic = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD

        var target: AccessibilityNodeInfo? = null
        for (root in candidateRoots()) {
            val hit = deepestAt(root, px, py) ?: continue
            var n: AccessibilityNodeInfo? = hit
            while (n != null) {
                if (n.isScrollable) {
                    target = n
                    break
                }
                n = n.parent
            }
            if (target != null) break
        }
        if (target == null) {
            val root = rootInActiveWindow
            if (root != null) target = biggestScrollable(root)
        }
        val t = target ?: return
        if (!t.performAction(specific)) t.performAction(generic)
    }

    private fun biggestScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = -1L
        val r = Rect()
        fun walk(n: AccessibilityNodeInfo) {
            if (n.isScrollable) {
                n.getBoundsInScreen(r)
                val area = r.width().toLong() * r.height().toLong()
                if (area > bestArea) {
                    bestArea = area
                    best = n
                }
            }
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                walk(c)
            }
        }
        walk(root)
        return best
    }

    // ------------------------------------------------------------------- texto

    private fun focusedInput(): AccessibilityNodeInfo? {
        for (root in candidateRoots()) {
            val f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (f != null && f.isEditable) return f
        }
        return null
    }

    /** Digita colando do clipboard: funciona com acentos e nao depende de ler o texto atual. */
    private fun typeText(s: String) {
        if (s.isEmpty()) return
        val n = focusedInput() ?: return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("texto", s))
        n.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    private fun backspace() {
        val n = focusedInput() ?: return
        val cur = n.text?.toString() ?: ""
        var s = n.textSelectionStart
        var e = n.textSelectionEnd
        if (s < 0 || e < 0) {
            s = cur.length
            e = cur.length
        }
        s = s.coerceAtMost(cur.length)
        e = e.coerceAtMost(cur.length)
        if (s > e) {
            val t = s
            s = e
            e = t
        }
        val newText: String
        val cursor: Int
        if (s != e) {
            newText = cur.removeRange(s, e)
            cursor = s
        } else if (s > 0) {
            newText = cur.removeRange(s - 1, s)
            cursor = s - 1
        } else {
            return
        }
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        val sel = Bundle()
        sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
        sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
        n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
    }

    private fun enter() {
        val n = focusedInput() ?: return
        if (Build.VERSION.SDK_INT >= 30) {
            n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        } else if (n.isMultiLine) {
            typeText("\n")
        }
    }

    // ---------------------------------------------------------- arvore de views

    /** Janelas de cima para baixo (teclado, barra de notificacoes, app). */
    private fun candidateRoots(): List<AccessibilityNodeInfo> {
        val res = ArrayList<AccessibilityNodeInfo>()
        try {
            val ws = windows.sortedByDescending { it.layer }
            for (w in ws) {
                val r = w.root
                if (r != null) res.add(r)
            }
        } catch (e: Exception) {
        }
        if (res.isEmpty()) {
            val r = rootInActiveWindow
            if (r != null) res.add(r)
        }
        return res
    }

    private fun deepestAt(node: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        val r = Rect()
        node.getBoundsInScreen(r)
        if (!r.contains(x, y) || !node.isVisibleToUser) return null
        for (i in node.childCount - 1 downTo 0) {
            val c = node.getChild(i) ?: continue
            val d = deepestAt(c, x, y)
            if (d != null) return d
        }
        return node
    }
}
