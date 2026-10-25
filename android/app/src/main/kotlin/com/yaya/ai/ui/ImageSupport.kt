package com.yaya.ai.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yaya.ai.ChatImages
import com.yaya.ai.ui.theme.YayaColors
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 单条消息最多携带的图片数：控制请求体与检查点体积。 */
const val MAX_CHAT_IMAGES = 4

private const val PICK_MAX_DIM = 1280
private const val VIEW_MAX_DIM = 1600
private const val REMOTE_MAX_BYTES = 12 * 1024 * 1024

/** 待发送图片：压缩后的 JPEG、data URL（下发 core）与预览位图。 */
data class PickedImage(
    val bytes: ByteArray,
    val dataUrl: String,
    val preview: ImageBitmap,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/** 相册选择结果 → 压缩 JPEG（最长边 1280，EXIF 方向在 API 28+ 由 ImageDecoder 处理）。 */
suspend fun prepareChatImage(context: Context, uri: Uri): PickedImage? =
    withContext(Dispatchers.IO) {
        val bitmap = try {
            decodePicked(context, uri)
        } catch (_: Exception) {
            null
        } ?: return@withContext null
        val out = ByteArrayOutputStream()
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)) return@withContext null
        val bytes = out.toByteArray()
        val dataUrl = "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        PickedImage(
            bytes = bytes,
            dataUrl = dataUrl,
            preview = bitmap.asImageBitmap(),
        )
    }

private fun decodePicked(context: Context, uri: Uri): Bitmap? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val longest = maxOf(w, h)
            if (longest > PICK_MAX_DIM) {
                val scale = PICK_MAX_DIM.toFloat() / longest
                decoder.setTargetSize(
                    (w * scale).toInt().coerceAtLeast(1),
                    (h * scale).toInt().coerceAtLeast(1),
                )
            }
        }
    }
    // API 24-27：两遍采样 + 精确缩放；ImageDecoder 不可用，EXIF 旋转未处理。
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, bounds)
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val sample = sampleSize(bounds.outWidth, bounds.outHeight, PICK_MAX_DIM)
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val decoded = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, opts)
    } ?: return null
    val longest = maxOf(decoded.width, decoded.height)
    if (longest <= PICK_MAX_DIM) return decoded
    val scale = PICK_MAX_DIM.toFloat() / longest
    val scaled = Bitmap.createScaledBitmap(
        decoded,
        (decoded.width * scale).toInt().coerceAtLeast(1),
        (decoded.height * scale).toInt().coerceAtLeast(1),
        true,
    )
    if (scaled !== decoded) decoded.recycle()
    return scaled
}

private fun sampleSize(width: Int, height: Int, maxDim: Int): Int {
    var sample = 1
    while (maxOf(width / sample, height / sample) > maxDim * 2) sample *= 2
    return sample
}

sealed interface ChatImageState {
    data object Loading : ChatImageState
    data class Ready(val bitmap: ImageBitmap) : ChatImageState
    data class Failed(val reason: String) : ChatImageState
}

/** 图片位图缓存（按来源键控；文件 / data URL / http(s) 共用）。 */
private object ChatImageCache {
    private val cache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun get(key: String): Bitmap? = cache.get(key)
    fun put(key: String, bitmap: Bitmap) = cache.put(key, bitmap)
    fun drop(key: String) = cache.remove(key)
}

/**
 * 按来源加载图片（任务 26）：本地受管文件 / `data:` URL / http(s) URL。
 * [attempt] 变化时重试；失败显式返回原因，不静默留白。
 */
@Composable
fun rememberChatImage(source: String, attempt: Int = 0): ChatImageState {
    val context = LocalContext.current
    val state by produceState<ChatImageState>(
        initialValue = ChatImageCache.get(source)
            ?.let { ChatImageState.Ready(it.asImageBitmap()) }
            ?: ChatImageState.Loading,
        key1 = source,
        key2 = attempt,
    ) {
        ChatImageCache.get(source)?.let {
            value = ChatImageState.Ready(it.asImageBitmap())
            return@produceState
        }
        value = ChatImageState.Loading
        val bitmap = withContext(Dispatchers.IO) { loadBitmap(context, source) }
        if (bitmap != null) {
            ChatImageCache.put(source, bitmap)
            value = ChatImageState.Ready(bitmap.asImageBitmap())
        } else {
            value = ChatImageState.Failed("图片加载失败")
        }
    }
    return state
}

private fun loadBitmap(context: Context, source: String): Bitmap? = try {
    when {
        source.startsWith("data:image/") -> {
            val b64 = source.substringAfter(";base64,", "")
            decodeBounded(Base64.decode(b64, Base64.DEFAULT), VIEW_MAX_DIM)
        }
        source.startsWith("http://") || source.startsWith("https://") -> {
            downloadBitmap(source)
        }
        ChatImages.isManaged(context, source) -> {
            val bytes = File(source).readBytes()
            decodeBounded(bytes, VIEW_MAX_DIM)
        }
        else -> null
    }
} catch (_: Exception) {
    null
}

private fun downloadBitmap(url: String): Bitmap? {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 15_000
        instanceFollowRedirects = true
        setRequestProperty("Accept", "image/*")
    }
    return try {
        if (conn.responseCode !in 200..299) return null
        val len = conn.contentLengthLong
        if (len > REMOTE_MAX_BYTES) return null
        val bytes = conn.inputStream.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                total += n
                if (total > REMOTE_MAX_BYTES) return null
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
        decodeBounded(bytes, VIEW_MAX_DIM)
    } finally {
        conn.disconnect()
    }
}

private fun decodeBounded(bytes: ByteArray, maxDim: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDim)
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
}

/** 消息内图片：加载中显示占位、失败可点重试（R15 不静默）。 */
@Composable
fun ChatImageBlock(
    source: String,
    onView: (String) -> Unit,
    modifier: Modifier = Modifier,
    thumb: Boolean = false,
) {
    var attempt by remember(source) { mutableStateOf(0) }
    val state = rememberChatImage(source, attempt)
    val shape = RoundedCornerShape(8.dp)
    val base = modifier.clip(shape)
    val sizing = if (thumb) Modifier else Modifier.fillMaxWidth()
    when (val s = state) {
        is ChatImageState.Ready -> Image(
            bitmap = s.bitmap,
            contentDescription = "图片",
            contentScale = if (thumb) ContentScale.Crop else ContentScale.Fit,
            modifier = base
                .then(if (thumb) Modifier else Modifier.fillMaxWidth().heightIn(max = 280.dp))
                .background(YayaColors.SurfaceHigh)
                .then(if (thumb) Modifier else Modifier.padding(top = 4.dp))
                .clickableNoRipple { onView(source) },
        )
        is ChatImageState.Loading -> Box(
            base.then(sizing).height(if (thumb) 96.dp else 140.dp)
                .background(YayaColors.SurfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        is ChatImageState.Failed -> Box(
            base.then(sizing).height(if (thumb) 96.dp else 120.dp)
                .background(YayaColors.SurfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "${s.reason}\n点击重试",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(8.dp)
                    .clickableNoRipple {
                        ChatImageCache.drop(source)
                        attempt += 1
                    },
            )
        }
    }
}

/** 无波纹点击（图片/文字重试等非按钮交互）。 */
@Composable
fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return clickable(interactionSource = interaction, indication = null, onClick = onClick)
}

/** 全屏看图：双指缩放 / 拖动，双击复位或放大。 */
@Composable
fun ChatImageViewer(source: String, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        var attempt by remember(source) { mutableStateOf(0) }
        val state = rememberChatImage(source, attempt)
        var scale by remember(source) { mutableFloatStateOf(1f) }
        var offset by remember(source) { mutableStateOf(Offset.Zero) }
        val transform = rememberTransformableState { zoomChange, panChange, _ ->
            val next = (scale * zoomChange).coerceIn(1f, 6f)
            scale = next
            offset = if (next <= 1f) Offset.Zero else offset + panChange
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when (val s = state) {
                is ChatImageState.Ready -> Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(source) {
                            detectTapGestures(onDoubleTap = {
                                if (scale > 1f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = 2.5f
                                }
                            })
                        }
                        .transformable(transform),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = s.bitmap,
                        contentDescription = "查看图片",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            },
                    )
                }
                is ChatImageState.Loading -> CircularProgressIndicator(
                    Modifier.align(Alignment.Center),
                    color = Color.White,
                )
                is ChatImageState.Failed -> Text(
                    "${s.reason}\n点击屏幕重试",
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clickableNoRipple {
                            ChatImageCache.drop(source)
                            attempt += 1
                        },
                )
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 36.dp, end = 12.dp)
                    .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(50)),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color.White)
            }
            Text(
                "双指缩放 · 双击复位",
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 28.dp),
            )
        }
    }
}
