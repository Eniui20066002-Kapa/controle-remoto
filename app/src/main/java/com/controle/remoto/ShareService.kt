package com.controle.remoto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Captura a tela (MediaProjection) e serve na rede local por TCP.
 *
 * Protocolo (nos dois sentidos): [4 bytes: tamanho][1 byte: tipo][conteudo]
 *   celular -> PC : tipo 1 = quadro JPEG, tipo 2 = texto ("OK", "ERR ...", "WARN ...")
 *   PC -> celular : tipo 10 = comando em texto (AUTH, TAP, SWIPE, ...)
 */
class ShareService : Service() {

    companion object {
        const val PORT = 8765

        @Volatile
        var running = false

        @Volatile
        var pin = ""

        @Volatile
        var clients = 0

        fun localIps(): List<String> {
            val res = ArrayList<String>()
            try {
                val en = NetworkInterface.getNetworkInterfaces()
                while (en != null && en.hasMoreElements()) {
                    val ni = en.nextElement()
                    if (!ni.isUp || ni.isLoopback) continue
                    val addrs = ni.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val a = addrs.nextElement()
                        if (a is Inet4Address && !a.isLoopbackAddress) {
                            val h = a.hostAddress
                            if (h != null) res.add(h)
                        }
                    }
                }
            } catch (e: Exception) {
            }
            return res
        }
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var capThread: HandlerThread? = null
    private var handler: Handler? = null
    private var server: ServerSocket? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val writeLock = Any()

    @Volatile
    private var clientSocket: Socket? = null

    @Volatile
    private var outStream: DataOutputStream? = null

    @Volatile
    private var stopping = false

    @Volatile
    private var maxSize = 960

    @Volatile
    private var quality = 55

    @Volatile
    private var minInterval = 125L

    private var capW = 2
    private var capH = 2
    private var dpi = 320
    private var bitmap: Bitmap? = null
    private var lastFrame = 0L
    private var lastWarn = 0L
    private var fails = 0
    private var lockUntil = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Precisa chamar startForeground logo, antes de qualquer outra coisa.
        startAsForeground()

        if (running) return START_NOT_STICKY
        val code = intent?.getIntExtra("code", 0) ?: 0
        val data: Intent? = intent?.getParcelableExtra("data")
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = try {
            mgr.getMediaProjection(code, data)
        } catch (e: Exception) {
            null
        }
        if (proj == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        pin = (100000 + SecureRandom().nextInt(900000)).toString()
        stopping = false

        val ht = HandlerThread("captura")
        ht.start()
        capThread = ht
        handler = Handler(ht.looper)

        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, handler)
        projection = proj

        acquireLocks()
        try {
            setupCapture()
            startServer()
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val channelId = "remoto"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Controle Remoto", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channelId) else Notification.Builder(this)
        val n = builder
            .setContentTitle("Controle Remoto")
            .setContentText("Compartilhando a tela na rede local")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "controle:cpu")
            wl.setReferenceCounted(false)
            wl.acquire()
            wakeLock = wl
        } catch (e: Exception) {
        }
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val l = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "controle:wifi")
            l.setReferenceCounted(false)
            l.acquire()
            wifiLock = l
        } catch (e: Exception) {
        }
    }

    // ------------------------------------------------------------------ captura

    private fun currentSize(): IntArray {
        val dm = DisplayMetrics()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm.defaultDisplay.getRealMetrics(dm)
        return intArrayOf(dm.widthPixels, dm.heightPixels, dm.densityDpi)
    }

    private fun scaledSize(w: Int, h: Int): IntArray {
        val m = maxOf(w, h)
        val s = if (m > maxSize) maxSize.toFloat() / m else 1f
        val cw = maxOf(2, (w * s).toInt() and 1.inv())
        val ch = maxOf(2, (h * s).toInt() and 1.inv())
        return intArrayOf(cw, ch)
    }

    private fun setupCapture() {
        val s = currentSize()
        dpi = s[2]
        val c = scaledSize(s[0], s[1])
        capW = c[0]
        capH = c[1]
        val r = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ rd -> onFrame(rd) }, handler)
        reader = r
        virtualDisplay = projection?.createVirtualDisplay(
            "remoto", capW, capH, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, handler
        )
    }

    /** Roda na thread de captura. Recria o leitor de imagens (rotacao, mudanca de qualidade, novo cliente). */
    private fun reconfigure() {
        if (stopping) return
        val s = currentSize()
        dpi = s[2]
        val c = scaledSize(s[0], s[1])
        capW = c[0]
        capH = c[1]
        val old = reader
        val r = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ rd -> onFrame(rd) }, handler)
        reader = r
        try {
            virtualDisplay?.resize(capW, capH, dpi)
            virtualDisplay?.surface = r.surface
        } catch (e: Exception) {
        }
        try {
            old?.close()
        } catch (e: Exception) {
        }
        bitmap = null
    }

    private fun onFrame(rd: ImageReader) {
        var needReconfig = false
        val img = try {
            rd.acquireLatestImage()
        } catch (e: Exception) {
            null
        }
        if (img != null) {
            try {
                val s = currentSize()
                val landscape = s[0] > s[1]
                if (landscape != (capW > capH)) {
                    needReconfig = true
                } else if (outStream != null) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastFrame >= minInterval) {
                        lastFrame = now
                        sendImage(img)
                    }
                }
            } catch (e: Exception) {
            } finally {
                img.close()
            }
        }
        if (needReconfig) reconfigure()
    }

    private fun sendImage(img: Image) {
        val plane = img.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * img.width
        val bw = img.width + rowPadding / pixelStride

        val bmp: Bitmap = bitmap?.takeIf { it.width == bw && it.height == img.height }
            ?: Bitmap.createBitmap(bw, img.height, Bitmap.Config.ARGB_8888).also { bitmap = it }
        bmp.copyPixelsFromBuffer(plane.buffer)

        val toSend = if (rowPadding == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, img.width, img.height)
        val bos = ByteArrayOutputStream(48 * 1024)
        toSend.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        if (toSend !== bmp) toSend.recycle()
        writeFrame(1, bos.toByteArray())
    }

    // --------------------------------------------------------------------- rede

    private fun startServer() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(PORT))
        server = ss
        thread(name = "aceitar") { acceptLoop(ss) }
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!stopping) {
            val s = try {
                ss.accept()
            } catch (e: Exception) {
                break
            }
            thread(name = "cliente") { handleClient(s) }
        }
    }

    private fun writeFrame(type: Int, payload: ByteArray) {
        val o = outStream ?: return
        try {
            synchronized(writeLock) {
                o.writeInt(payload.size)
                o.writeByte(type)
                o.write(payload)
                o.flush()
            }
        } catch (e: Exception) {
            closeClient()
        }
    }

    private fun writeDirect(o: DataOutputStream, type: Int, text: String) {
        val b = text.toByteArray(Charsets.UTF_8)
        synchronized(writeLock) {
            o.writeInt(b.size)
            o.writeByte(type)
            o.write(b)
            o.flush()
        }
    }

    private fun readFrame(din: DataInputStream): Pair<Int, ByteArray> {
        val len = din.readInt()
        val type = din.readUnsignedByte()
        if (len < 0 || len > 1_000_000) throw IOException("quadro invalido")
        val buf = ByteArray(len)
        din.readFully(buf)
        return Pair(type, buf)
    }

    @Synchronized
    private fun registerFail() {
        fails++
        if (fails >= 5) {
            fails = 0
            lockUntil = SystemClock.elapsedRealtime() + 60_000L
        }
    }

    @Synchronized
    private fun isLocked(): Boolean = SystemClock.elapsedRealtime() < lockUntil

    private fun closeClient() {
        val s = clientSocket
        outStream = null
        clientSocket = null
        clients = 0
        try {
            s?.close()
        } catch (e: Exception) {
        }
    }

    private fun handleClient(s: Socket) {
        try {
            s.tcpNoDelay = true
            s.soTimeout = 10000
            val din = DataInputStream(BufferedInputStream(s.getInputStream()))
            val dout = DataOutputStream(BufferedOutputStream(s.getOutputStream()))

            if (isLocked()) {
                writeDirect(dout, 2, "ERR Muitas tentativas erradas. Aguarde 1 minuto.")
                return
            }
            val first = readFrame(din)
            val txt = String(first.second, Charsets.UTF_8)
            if (first.first != 10 || txt != "AUTH $pin") {
                registerFail()
                writeDirect(dout, 2, "ERR PIN incorreto")
                Thread.sleep(1000)
                return
            }
            fails = 0
            s.soTimeout = 0

            closeClient() // so um PC por vez: o novo substitui o antigo
            writeDirect(dout, 2, "OK")
            clientSocket = s
            outStream = dout
            clients = 1
            handler?.post { reconfigure() } // forca um quadro novo, mesmo com a tela parada

            while (!stopping) {
                val f = readFrame(din)
                if (f.first == 10) handleCommand(String(f.second, Charsets.UTF_8))
            }
        } catch (e: Exception) {
        } finally {
            if (clientSocket === s) {
                outStream = null
                clientSocket = null
                clients = 0
            }
            try {
                s.close()
            } catch (e: Exception) {
            }
        }
    }

    private fun warn(msg: String) {
        val n = SystemClock.elapsedRealtime()
        if (n - lastWarn > 4000) {
            lastWarn = n
            writeFrame(2, ("WARN " + msg).toByteArray(Charsets.UTF_8))
        }
    }

    private fun handleCommand(cmd: String) {
        val sp = cmd.indexOf(' ')
        val name = if (sp < 0) cmd else cmd.substring(0, sp)
        val arg = if (sp < 0) "" else cmd.substring(sp + 1)
        when (name) {
            "CFG" -> applyConfig(arg)
            "REFRESH" -> handler?.post { reconfigure() }
            "WAKE" -> wake()
            else -> {
                val svc = ControlService.instance
                if (svc == null) {
                    warn("Controle desativado no celular. Ative em Configuracoes > Acessibilidade > Controle Remoto.")
                } else {
                    svc.handle(name, arg)
                }
            }
        }
    }

    private fun applyConfig(arg: String) {
        try {
            val p = arg.trim().split(" ")
            val ms = p[0].toInt().coerceIn(320, 2000)
            val q = p[1].toInt().coerceIn(10, 95)
            val fps = p[2].toInt().coerceIn(1, 30)
            quality = q
            minInterval = 1000L / fps
            if (ms != maxSize) {
                maxSize = ms
                handler?.post { reconfigure() }
            }
        } catch (e: Exception) {
        }
    }

    private fun wake() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "controle:wake"
            )
            wl.acquire(5000L)
        } catch (e: Exception) {
        }
    }

    override fun onDestroy() {
        stopping = true
        running = false
        closeClient()
        try {
            server?.close()
        } catch (e: Exception) {
        }
        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
        }
        try {
            reader?.close()
        } catch (e: Exception) {
        }
        try {
            projection?.stop()
        } catch (e: Exception) {
        }
        capThread?.quitSafely()
        try {
            wakeLock?.release()
        } catch (e: Exception) {
        }
        try {
            wifiLock?.release()
        } catch (e: Exception) {
        }
        super.onDestroy()
    }
}
