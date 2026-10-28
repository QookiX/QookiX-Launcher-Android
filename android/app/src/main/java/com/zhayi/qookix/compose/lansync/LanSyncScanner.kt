package com.zhayi.qookix.compose.lansync

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.zhayi.qookix.R
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * 局域网同步扫码器：CameraX 预览 + ZXing 实时解码二维码。
 *
 * 扫到二维码后调 [onScanned]（内容应是 `qookix://lan/pair?host=…&port=…&token=…`）。
 * 首次进入自动请求相机权限；拒绝则显示授权按钮。
 *
 * @param onScanned 扫到内容后回调（主线程）。回调后本组件不再重复触发。
 */
@Composable
fun LanSyncScanner(
    onScanned: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (!hasCameraPermission) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    stringResource(R.string.lan_sync_camera_perm),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text(stringResource(R.string.lan_sync_grant_perm))
                }
            }
        }
        return
    }

    // ── 相机预览 + 帧分析 ──────────────────────────────────────────────
    val previewView = remember { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val reader = remember { MultiFormatReader() }
    // 用 AtomicBoolean 而不是 Compose state：这个标记只被分析线程读写，
    // 从非主线程写 snapshot state 没有意义，还会和重组抢。
    val scanned = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    // 解绑相机要用：LaunchedEffect 取消时不一定拿到局部变量
    val providerRef = remember { java.util.concurrent.atomic.AtomicReference<ProcessCameraProvider?>(null) }
    val analysisRef = remember { java.util.concurrent.atomic.AtomicReference<ImageAnalysis?>(null) }

    AndroidView(
        factory = { previewView },
        modifier = modifier.fillMaxSize(),
    )

    LaunchedEffect(previewView) {
        val cameraProvider = suspendCancellableCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                { cont.resume(future.get()) },
                ContextCompat.getMainExecutor(context),
            )
        }
        providerRef.set(cameraProvider)

        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysisRef.set(analysis)
        analysis.setAnalyzer(analysisExecutor) { image ->
            // 分析线程：任何一帧解码失败都不能影响后面的帧
            try {
                if (!scanned.get()) {
                    val result = decodeQr(image, reader)
                    if (result != null && scanned.compareAndSet(false, true)) {
                        // onScanned 会去改 Compose 状态 / 跳转，必须回主线程
                        mainHandler.post { onScanned(result) }
                    }
                }
            } catch (_: Throwable) {
                // 忽略（畸形帧 / 解码器内部错误）
            } finally {
                image.close()
            }
        }

        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            analysis,
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            // ⚠️ 顺序很重要：先停分析、再解绑相机，最后才关线程池。
            // 原来只 shutdown 线程池、不 unlink 相机 —— 离开扫码页后相机还在出帧，
            // CameraX 继续往已关闭的 executor 提交任务，抛 RejectedExecutionException
            // 把整个应用带崩（进一次扫码页再返回就能复现）。
            runCatching { analysisRef.get()?.clearAnalyzer() }
            runCatching { providerRef.get()?.unbindAll() }
            analysisExecutor.shutdown()
        }
    }
}

// ── ZXing 解码 ───────────────────────────────────────────────────────────

private fun decodeQr(image: ImageProxy, reader: MultiFormatReader): String? {
    val frame = image.toLuminance() ?: return null
    return decodeLuminance(frame.data, frame.width, frame.height, reader)
}

/**
 * 把一帧亮度数据解成二维码内容；解不出返回 null。
 *
 * ⚠️ 2026-10-28 修（真机反馈「扫码没反应」）：
 * 原来这里写的是 `listOfNotNull(source, source.rotateCounterClockwise())` ——
 * `PlanarYUVLuminanceSource` 的 `isRotateSupported()` 恒为 false，
 * `rotateCounterClockwise()` 直接抛 `UnsupportedOperationException`。
 * 这行在 try 外面，异常一路冒到分析线程的外层 catch(Throwable) 被吞掉，
 * **每一帧都失败**，所以扫什么码都没有任何反应。
 *
 * 现在自己旋转字节数组：先试原方向，再试逆时针 / 顺时针 90°（相机传感器
 * 是横向的，竖持手机时二维码在帧里是躺倒的，不转会明显更难识别）。
 *
 * 抽成独立函数是为了能写 JVM 单测（QRCodeWriter 编码 → 本函数解码）。
 */
internal fun decodeLuminance(
    data: ByteArray,
    width: Int,
    height: Int,
    reader: MultiFormatReader,
): String? {
    if (width <= 0 || height <= 0 || data.size < width * height) return null

    val original = PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
    decodeWith(original, reader)?.let { return it }

    // 逆时针 90°：新图宽 = height，高 = width
    val ccw = rotateCounterClockwise(data, width, height)
    decodeWith(
        PlanarYUVLuminanceSource(ccw, height, width, 0, 0, height, width, false),
        reader,
    )?.let { return it }

    return null
}

private fun decodeWith(source: PlanarYUVLuminanceSource, reader: MultiFormatReader): String? =
    try {
        reader.decode(BinaryBitmap(HybridBinarizer(source))).text
    } catch (_: NotFoundException) {
        null
    } catch (_: Exception) {
        // ChecksumException / FormatException：这一帧糊了，继续
        null
    }

/**
 * 逆时针旋转 90°（行优先，`out[ny * newW + nx]`）。
 *
 * 逆时针后：新宽 = h、新高 = w，新坐标 `(nx, ny)` 对应原 `(x, y) = (ny, w - 1 - nx)`。
 */
internal fun rotateCounterClockwise(data: ByteArray, w: Int, h: Int): ByteArray {
    val out = ByteArray(w * h)
    for (y in 0 until h) {
        for (x in 0 until w) {
            // 原 (x, y) → 新 (nx = y, ny = w - 1 - x)
            out[(w - 1 - x) * h + y] = data[y * w + x]
        }
    }
    return out
}

/** 一帧亮度画面（解码用，和 CameraX / Android 解耦，方便单测）。 */
internal class LuminanceFrame(val data: ByteArray, val width: Int, val height: Int)

/** 把 CameraX 的 YUV_420_888 帧的 Y 平面（亮度）取出来。 */
private fun ImageProxy.toLuminance(): LuminanceFrame? {
    if (format != ImageFormat.YUV_420_888) return null
    val yPlane = planes[0]
    val yBuffer = yPlane.buffer
    val w = width
    val h = height
    val rowStride = yPlane.rowStride
    yBuffer.rewind()
    val data = ByteArray(w * h)
    if (rowStride == w && yBuffer.remaining() >= w * h) {
        yBuffer.get(data, 0, w * h)
    } else {
        // rowStride > width：逐行拷贝，去掉 padding。
        // 相机给的最后一行经常不满 stride，越界就读到 limit 之外 —— 逐行按
        // remaining() 收紧，宁可这一帧下半部是黑的也不要抛 BufferUnderflow。
        val temp = ByteArray(rowStride)
        var offset = 0
        for (row in 0 until h) {
            val pos = row * rowStride
            if (pos >= yBuffer.limit()) break
            yBuffer.position(pos)
            val n = minOf(rowStride, yBuffer.remaining())
            yBuffer.get(temp, 0, n)
            val copy = minOf(w, n)
            System.arraycopy(temp, 0, data, offset, copy)
            offset += copy
        }
    }
    return LuminanceFrame(data, w, h)
}
