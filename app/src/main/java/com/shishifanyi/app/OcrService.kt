package com.shishifanyi.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * 截屏 + ML Kit OCR：无障碍读不到文字时兜底，同时提供背景色采样。
 */
class OcrService : Service() {

    companion object {
        private const val TAG = "LiveTranslate"
        private const val CH_ID = "live_translate_ocr"

        /** 识别用最大宽度；超过则降采样，行框按比例还原到屏幕像素。 */
        private const val OCR_MAX_WIDTH = 1080

        /** 识别器空闲这么久后关闭，释放原生模型内存。 */
        private const val OCR_IDLE_MS = 180_000L

        @Volatile
        var pendingData: Intent? = null
            private set

        @Volatile
        private var running = false

        fun hasPermission(): Boolean = pendingData != null

        fun setPermission(data: Intent?) {
            pendingData = data
        }

        fun start(ctx: Context) {
            try {
                val i = Intent(ctx, OcrService::class.java)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (t: Throwable) {
                Log.w(TAG, "启动 OCR 服务失败: " + t.message)
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, OcrService::class.java))
            } catch (ex: Throwable) {
                Log.w(TAG, "OcrService@63: " + ex.message)
            }
        }

        fun isRunning(): Boolean = running
    }

    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    @Volatile
    private var recognizer: com.google.mlkit.vision.text.TextRecognizer? = null

    private val closeTask = Runnable {
        try {
            recognizer?.close()
        } catch (ex: Throwable) {
            Log.w(TAG, "关闭识别器失败: " + ex.message)
        }
        recognizer = null
        Log.d(TAG, "OCR 识别器空闲关闭，释放模型内存")
    }

    private fun recognizerOrCreate(): com.google.mlkit.vision.text.TextRecognizer {
        var r = recognizer
        if (r == null) {
            r = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer = r
        }
        main.removeCallbacks(closeTask)
        main.postDelayed(closeTask, OCR_IDLE_MS)
        return r
    }

    /** 只留一张缩略图取色，避免常驻一整张全屏位图（约 12MB）。 */
    @Volatile
    private var smallFrame: Bitmap? = null

    @Volatile
    private var smallScale: Float = 1f

    @Volatile
    private var lastFrameAt: Long = 0L

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            releaseCapture()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        AppCore.init(this)
        startForegroundSafely()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val data = pendingData
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection == null) setup(data)
        running = projection != null
        OcrBridge.attach(this)
        return START_STICKY
    }

    private fun startForegroundSafely() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = getSystemService(NotificationManager::class.java)
                if (nm.getNotificationChannel(CH_ID) == null) {
                    val ch = NotificationChannel(CH_ID, "截屏识别", NotificationManager.IMPORTANCE_MIN)
                    ch.setShowBadge(false)
                    nm.createNotificationChannel(ch)
                }
            }
            val builder = if (Build.VERSION.SDK_INT >= 26)
                Notification.Builder(this, CH_ID)
            else
                @Suppress("DEPRECATION")
                Notification.Builder(this)

            val n = builder
                .setContentTitle("实时翻译")
                .setContentText("截屏识别待命")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build()

            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(2001, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(2001, n)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "前台服务启动失败: " + t.message)
        }
    }

    private fun setup(data: Intent) {
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            // token 是一次性的：先作废再取，取失败也不会留下"有权限"的假象
            setPermission(null)
            val p = mpm.getMediaProjection(Activity.RESULT_OK, data) ?: run {
                Log.w(TAG, "getMediaProjection 返回空，token 已作废")
                return
            }
            // registerCallback 从 API 21 就有，以前只在 34+ 注册：
            // 24~33 上系统收回投影后我们依然以为「可用」，于是每屏白跑一次建镜像、
            // 而且因为 isRunning() 还是 true，界面再也不会提示重新授权
            try {
                p.registerCallback(projectionCallback, main)
            } catch (t2: Throwable) {
                Log.w(TAG, "注册投影回调失败: " + t2.message)
            }
            projection = p

            Log.d(TAG, "投影就绪（按需开镜像）")
        } catch (t: Throwable) {
            // 失败同样要作废，否则这一屏的 token 会卡住后续所有启动
            setPermission(null)
            Log.w(TAG, "OCR 采集初始化失败: " + t.message)
            releaseCapture()
        }
    }

    /** 抓一帧并识别成带位置的文字块，回调在主线程。 */
    fun captureBlocks(cb: (List<TextBlock>) -> Unit) {
        main.post { doCapture(cb, 6) }
    }

    /** 需要时才创建镜像，抓到一帧后马上拆掉，避免后台一直录屏。 */
    private fun ensureSurface(): Boolean {
        if (reader != null) return true
        val p = projection ?: return false
        return try {
            val dm = resources.displayMetrics
            // 尺寸必须与 AppCore / OverlayService 同源（maximumWindowMetrics 优先）：
            // 镜像按 displayMetrics 建、区域按窗口度量算，两套坐标系会让行框错位或整块漏译
            val (sw, sh) = AppCore.screenSize()
            val w = if (sw > 0) sw else dm.widthPixels
            val h = if (sh > 0) sh else dm.heightPixels
            val r = ImageReader.newInstance(
                // 这里用 PixelFormat 是对的：ImageFormat 里没有 RGBA_8888，
                // ImageReader 接受 PixelFormat.RGBA_8888（与 ImageFormat 的编号体系不同）
                w, h, PixelFormat.RGBA_8888, 1
            )
            val vd = p.createVirtualDisplay(
                "live-translate-ocr",
                w, h, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface, null, main
            )
            reader = r
            virtualDisplay = vd
            true
        } catch (t: Throwable) {
            Log.w(TAG, "建镜像失败: " + t.message)
            releaseSurface()
            false
        }
    }

    private fun releaseSurface() {
        try {
            virtualDisplay?.release()
        } catch (ex: Throwable) {
            Log.w(TAG, "OcrService@193: " + ex.message)
        }
        virtualDisplay = null
        try {
            reader?.close()
        } catch (ex: Throwable) {
            Log.w(TAG, "OcrService@198: " + ex.message)
        }
        reader = null
    }

    private fun doCapture(cb: (List<TextBlock>) -> Unit, tries: Int) {
        OcrBridge.clearSecureFrame()
        if (!ensureSurface()) {
            cb(emptyList())
            return
        }
        val r = reader
        if (r == null) {
            cb(emptyList())
            return
        }
        var img: Image? = null
        try {
            img = r.acquireLatestImage()
        } catch (t: Throwable) {
            img = null
        }
        if (img == null) {
            if (tries > 0) {
                main.postDelayed({ doCapture(cb, tries - 1) }, 80L)
            } else {
                releaseSurface()
                cb(emptyList())
            }
            return
        }
        val bmp = toBitmap(img)
        try {
            img.close()
        } catch (ex: Throwable) {
            Log.w(TAG, "OcrService@231: " + ex.message)
        }
        if (bmp == null) {
            releaseSurface()
            cb(emptyList())
            return
        }
        releaseSurface()   // 帧已拿到，立刻停止镜像

        // 全分辨率 ARGB 约 18MB，识别前降到宽 ≤1080 可显著降低内存抖动
        var ocrBmp: android.graphics.Bitmap? = null
        var scale = 1f
        if (bmp.width > OCR_MAX_WIDTH) {
            scale = OCR_MAX_WIDTH.toFloat() / bmp.width
            ocrBmp = try {
                android.graphics.Bitmap.createScaledBitmap(
                    bmp,
                    OCR_MAX_WIDTH,
                    (bmp.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } catch (t: Throwable) {
                Log.w(TAG, "降采样失败，改用原图: " + t.message)
                null
            }
            if (ocrBmp != null) {
                try {
                    bmp.recycle()
                } catch (ex: Throwable) {
                    Log.w(TAG, "OcrService@249: " + ex.message)
                }
            } else {
                scale = 1f          // 降采样失败则按原图处理，比例必须归位
            }
        }
        val useBmp = ocrBmp ?: bmp
        keepSmall(useBmp, scale)
        // 安全窗口（FLAG_SECURE）只把**应用内容**涂黑，状态栏/导航栏仍会正常出现在截图里，
        // 所以不能"整帧纯色"就判定，否则真实的安全窗口识别不出来，
        // 反而纯白/纯色空白页会误报。这里只采样中间区域，并且要 OCR 也读不出东西才算数。
        val blank = looksBlank(useBmp)
        recognizerOrCreate().process(InputImage.fromBitmap(useBmp, 0))
            .addOnSuccessListener { result ->
                try {
                    useBmp.recycle()
                } catch (ex: Throwable) {
                    Log.w(TAG, "OcrService@244: " + ex.message)
                }
                val out = ArrayList<TextBlock>()
                for (tb in result.textBlocks) {
                    for (line in tb.lines) {
                        val box = line.boundingBox ?: continue
                        // 坐标还原到屏幕像素：识别用的是缩小图
                        val r = if (scale == 1f) Rect(box) else Rect(
                            (box.left / scale).toInt(),
                            (box.top / scale).toInt(),
                            (box.right / scale).toInt(),
                            (box.bottom / scale).toInt()
                        )
                        out.add(TextBlock(line.text, r))
                    }
                }
                if (out.isEmpty() && blank) OcrBridge.markSecureFrame()
                cb(out)
            }
            .addOnFailureListener { e ->
                try {
                    useBmp.recycle()
                } catch (ex: Throwable) {
                    Log.w(TAG, "OcrService@258: " + ex.message)
                }
                Log.w(TAG, "OCR 失败: " + e.message)
                cb(emptyList())
            }
    }

    /** 生成一张小图专门用于取色，省掉一张常驻全屏位图。 */
    /**
     * @param baseScale 该位图相对**屏幕像素**的比例（降采样时 < 1）。
     *   sampleColor 收到的是屏幕坐标，所以 smallScale 必须是"屏幕 → 缩略图"的总比例，
     *   只按缩略图自身尺寸算会差一个降采样倍数，取色点会偏向右下并越界。
     */
    private fun keepSmall(bmp: Bitmap, baseScale: Float = 1f) {
        val old = smallFrame
        try {
            val maxDim = 720
            val shrink = maxDim.toFloat() / maxOf(bmp.width, bmp.height).coerceAtLeast(1)
            val total = baseScale * shrink
            val scale = shrink
            smallFrame = if (shrink < 1f) {
                smallScale = total
                Bitmap.createScaledBitmap(
                    bmp,
                    (bmp.width * scale).toInt().coerceAtLeast(1),
                    (bmp.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                // 原图且尺寸不大：另建副本，避免回调回收后取色失效
                smallScale = 1f
                Bitmap.createBitmap(bmp)
            }
        } catch (t: Throwable) {
            smallFrame = null
            smallScale = 1f
        }
        lastFrameAt = SystemClock.elapsedRealtime()
        try {
            if (old != null && old !== smallFrame && !old.isRecycled) old.recycle()
        } catch (ex: Throwable) {
            Log.w(TAG, "OcrService@290: " + ex.message)
        }
    }

    /**
     * 取该矩形四角像素的平均色，用作译文底色；背景不统一时返回 null。
     */
    fun sampleColor(rect: Rect): Int? {
        val bmp = smallFrame ?: return null
        if (bmp.isRecycled) return null
        if (SystemClock.elapsedRealtime() - lastFrameAt > 2500L) return null

        val s = smallScale
        val xs = intArrayOf(
            (rect.left * s).toInt() + 1,
            (rect.right * s).toInt() - 1
        )
        val ys = intArrayOf(
            (rect.top * s).toInt() + 1,
            (rect.bottom * s).toInt() - 1
        )
        var n = 0
        var ar = 0
        var ag = 0
        var ab = 0
        val samples = ArrayList<Int>(4)
        for (x in xs) {
            for (y in ys) {
                if (x < 0 || y < 0 || x >= bmp.width || y >= bmp.height) continue
                val c = bmp.getPixel(x, y)
                samples.add(c)
                ar += (c shr 16) and 0xFF
                ag += (c shr 8) and 0xFF
                ab += c and 0xFF
                n++
            }
        }
        if (n == 0) return null

        // 四角差异过大说明压在图案上，交给默认底色
        var minL = 999
        var maxL = -1
        for (c in samples) {
            val l = (c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)
            if (l < minL) minL = l
            if (l > maxL) maxL = l
        }
        if (maxL - minL > 120) return null

        return (0xFF shl 24) or ((ar / n) shl 16) or ((ag / n) shl 8) or (ab / n)
    }

    /**
     * 采样判断内容区域是不是「一片纯黑」。两个条件缺一不可：
     *  - 中间 80% 区域亮度极差很小（避开状态栏/导航栏，FLAG_SECURE 只涂黑应用内容）
     *  - 且整体很暗（亮度上限 ≤ 48）—— 纯白/浅色空白页同样"纯色"，
     *    只按极差判断会把它们误报成安全窗口
     */
    private fun looksBlank(bmp: android.graphics.Bitmap): Boolean {
        return try {
            val x0 = (bmp.width * 0.1f).toInt()
            val x1 = (bmp.width * 0.9f).toInt().coerceAtMost(bmp.width - 1)
            val y0 = (bmp.height * 0.1f).toInt()
            val y1 = (bmp.height * 0.9f).toInt().coerceAtMost(bmp.height - 1)
            val stepX = ((x1 - x0) / 16).coerceAtLeast(1)
            val stepY = ((y1 - y0) / 16).coerceAtLeast(1)
            var min = 255
            var max = 0
            var x = x0
            while (x <= x1) {
                var y = y0
                while (y <= y1) {
                    val c = bmp.getPixel(x, y)
                    val l = (((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)) / 3
                    if (l < min) min = l
                    if (l > max) max = l
                    y += stepY
                }
                x += stepX
            }
            max - min <= 6 && max <= 48
        } catch (t: Throwable) {
            false
        }
    }

    private fun toBitmap(image: Image): Bitmap? {
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val w = image.width + rowPadding / pixelStride
            val bmp = Bitmap.createBitmap(w, image.height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buffer)
            if (w != image.width) {
                val cropped = Bitmap.createBitmap(bmp, 0, 0, image.width, image.height)
                bmp.recycle()
                cropped
            } else {
                bmp
            }
        } catch (t: Throwable) {
            Log.w(TAG, "帧转位图失败: " + t.message)
            null
        }
    }

    private fun releaseCapture() {
        releaseSurface()
        try {
            projection?.stop()
        } catch (ex: Throwable) {
            Log.w(TAG, "OcrService@369: " + ex.message)
        }
        projection = null
        running = false
        OcrBridge.detach(this)
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // 用白名单而不是 >=：BACKGROUND(40) 也 ≥ RUNNING_CRITICAL(15)，
        // 按阈值判断会在进程进入后台 LRU 时清掉模型。
        // 只认这三种明确的压力等级，UI_HIDDEN 与 BACKGROUND 都不清。
        val pressure = level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level == android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE ||
            level == android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        if (pressure) {
            main.removeCallbacks(closeTask)
            try {
                recognizer?.close()
            } catch (_: Throwable) {
            }
            recognizer = null
            Log.d(TAG, "内存紧张，已释放 OCR 识别器")
        }
    }

    override fun onDestroy() {
        main.removeCallbacks(closeTask)
        try {
            recognizer?.close()
        } catch (_: Throwable) {
        }
        recognizer = null
        running = false
        OcrBridge.detach(this)
        releaseCapture()
        val f = smallFrame
        smallFrame = null
        try {
            if (f != null && !f.isRecycled) f.recycle()
        } catch (ex: Throwable) {
            Log.w(TAG, "OcrService@384: " + ex.message)
        }
        super.onDestroy()
    }
}

/** 供其它模块调用 OCR 的桥。 */
object OcrBridge {
    @Volatile
    private var svc: OcrService? = null

    /** 上一帧是不是「一片纯色」：安全窗口（FLAG_SECURE）被截出来就是这样。 */
    @Volatile
    private var secureFrame = false

    fun attach(s: OcrService) {
        svc = s
    }

    fun detach(s: OcrService) {
        if (svc === s) svc = null
    }

    fun available(): Boolean = svc != null

    fun clearSecureFrame() {
        secureFrame = false
    }

    fun markSecureFrame() {
        secureFrame = true
    }

    /** 取走并清掉「这帧是安全窗口」的标记。 */
    fun consumeSecureFrame(): Boolean {
        val v = secureFrame
        secureFrame = false
        return v
    }

    fun captureBlocks(cb: (List<TextBlock>) -> Unit) {
        val s = svc
        if (s == null) {
            cb(emptyList())
            return
        }
        s.captureBlocks(cb)
    }

    fun sampleColor(rect: Rect): Int? {
        return svc?.sampleColor(rect)
    }
}
