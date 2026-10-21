package com.controle.remoto

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private val reqCapture = 1001
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var btnStart: Button

    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad, pad, pad)

        val title = TextView(this)
        title.text = "Controle Remoto"
        title.textSize = 24f
        title.typeface = Typeface.DEFAULT_BOLD

        val help = TextView(this)
        help.text = "Passo 1: ative o controle. Passo 2: inicie o compartilhamento. " +
            "Depois digite o IP e o PIN no programa do PC."
        help.textSize = 14f
        help.setPadding(0, pad / 2, 0, pad / 2)

        val btnAcc = Button(this)
        btnAcc.text = "1) Ativar o controle (Acessibilidade)"
        btnAcc.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnStart = Button(this)
        btnStart.setOnClickListener { onStartStop() }

        status = TextView(this)
        status.textSize = 16f
        status.setPadding(0, pad, 0, pad)

        root.addView(title)
        root.addView(help)
        root.addView(btnAcc)
        root.addView(btnStart)
        root.addView(status)

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        ui.post(refresher)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(refresher)
    }

    private fun onStartStop() {
        if (ShareService.running) {
            stopService(Intent(this, ShareService::class.java))
        } else {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mgr.createScreenCaptureIntent(), reqCapture)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqCapture && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, ShareService::class.java)
            i.putExtra("code", resultCode)
            i.putExtra("data", data)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        }
    }

    private fun refresh() {
        val acc = ControlService.instance != null
        val sb = StringBuilder()
        sb.append("Controle (Acessibilidade): ")
        sb.append(if (acc) "ATIVO" else "DESATIVADO (faca o passo 1)")
        sb.append("\n\n")
        if (ShareService.running) {
            sb.append("Compartilhando a tela.\n\nNo PC, use:\n")
            val ips = ShareService.localIps()
            if (ips.isEmpty()) sb.append("  (sem IP - conecte ao Wi-Fi)\n")
            for (ip in ips) sb.append("  IP: ").append(ip).append("\n")
            val p = ShareService.pin
            val pretty = if (p.length == 6) p.substring(0, 3) + " " + p.substring(3) else p
            sb.append("  PIN: ").append(pretty).append("\n")
            sb.append("  Porta: ").append(ShareService.PORT).append("\n\n")
            sb.append(if (ShareService.clients > 0) "PC conectado." else "Aguardando o PC...")
        } else {
            sb.append("Compartilhamento parado.")
        }
        status.text = sb.toString()
        btnStart.text = if (ShareService.running) "Parar compartilhamento" else "2) Iniciar compartilhamento"
    }
}
