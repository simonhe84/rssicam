package se.cit.rssicam

import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Turns an ARCore YUV camera image into a JPEG, embeds the metadata JSON in
 * EXIF (UserComment + ImageDescription) and publishes the photo to
 *   Pictures/RSSICam/<session>/   (via MediaStore, visible to gallery & PC)
 * plus a sidecar .json to
 *   Documents/RSSICam/<session>/
 */
class CaptureStore(private val context: Context) {

    data class Result(val imageUri: Uri?, val jsonUri: Uri?, val fileName: String, val exifBytes: Int)

    /** Raw copy of a YUV_420_888 image, taken on the GL thread, converted later. */
    class YuvCopy(val width: Int, val height: Int, val nv21: ByteArray)

    fun copyImage(image: Image): YuvCopy = YuvCopy(image.width, image.height, yuv420ToNv21(image))

    fun save(session: String, yuv: YuvCopy, meta: JSONObject, exifOrientation: Int, jpegQuality: Int = 95): Result {
        val safeSession = session.trim().ifEmpty { "default" }.replace(Regex("[^A-Za-z0-9_\\-\\u4e00-\\u9fff]"), "_")
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val baseName = "RSSI_${stamp}"

        // 1. JPEG into a temp file
        val tmp = File(context.cacheDir, "$baseName.jpg")
        FileOutputStream(tmp).use { out ->
            YuvImage(yuv.nv21, ImageFormat.NV21, yuv.width, yuv.height, null)
                .compressToJpeg(Rect(0, 0, yuv.width, yuv.height), jpegQuality, out)
        }

        // 2. EXIF
        val json = asciiJson(meta)
        val exif = ExifInterface(tmp.absolutePath)
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, exifOrientation.toString())
        exif.setAttribute(ExifInterface.TAG_SOFTWARE, "RSSICam 1.0 (ARCore)")
        exif.setAttribute(ExifInterface.TAG_MAKE, android.os.Build.MANUFACTURER)
        exif.setAttribute(ExifInterface.TAG_MODEL, android.os.Build.MODEL)
        val exifDate = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date())
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, exifDate)
        exif.setAttribute(ExifInterface.TAG_DATETIME, exifDate)
        exif.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, yuv.width.toString())
        exif.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, yuv.height.toString())
        // Short human-readable summary
        val p = meta.optJSONObject("pose")
        val summary = if (p != null) "RSSICam pose t=(%.3f,%.3f,%.3f) q=(%.4f,%.4f,%.4f,%.4f) wifi=%d ble=%d".format(
            Locale.US, p.optDouble("tx"), p.optDouble("ty"), p.optDouble("tz"),
            p.optDouble("qx"), p.optDouble("qy"), p.optDouble("qz"), p.optDouble("qw"),
            meta.optJSONObject("wifi")?.optInt("count") ?: 0, meta.optJSONObject("ble")?.optInt("count") ?: 0
        ) else "RSSICam"
        exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, summary)
        // Full JSON (EXIF APP1 segment must stay < 64 KB)
        exif.setAttribute(ExifInterface.TAG_USER_COMMENT, json)
        exif.saveAttributes()

        // 3. Publish JPEG via MediaStore
        val resolver = context.contentResolver
        val imgValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$baseName.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/RSSICam/$safeSession")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val imgUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imgValues)
        if (imgUri != null) {
            resolver.openOutputStream(imgUri)?.use { out -> tmp.inputStream().use { it.copyTo(out) } }
            imgValues.clear()
            imgValues.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(imgUri, imgValues, null, null)
        }
        tmp.delete()

        // 4. Sidecar JSON (pretty, full unicode) via MediaStore Files -> Documents/
        val jsonValues = ContentValues().apply {
            put(MediaStore.Files.FileColumns.DISPLAY_NAME, "$baseName.json")
            put(MediaStore.Files.FileColumns.MIME_TYPE, "application/json")
            put(MediaStore.Files.FileColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOCUMENTS}/RSSICam/$safeSession")
        }
        val jsonUri = runCatching {
            resolver.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), jsonValues)
        }.getOrNull()
        if (jsonUri != null) {
            runCatching {
                resolver.openOutputStream(jsonUri)?.use { it.write(meta.toString(2).toByteArray(Charsets.UTF_8)) }
            }
        }
        return Result(imgUri, jsonUri, "$baseName.jpg", json.length)
    }

    /** JSON with all non-ASCII escaped as \\uXXXX so it survives EXIF ASCII UserComment. */
    private fun asciiJson(o: JSONObject): String {
        val s = o.toString()
        val sb = StringBuilder(s.length + 64)
        for (ch in s) {
            if (ch.code in 0x20..0x7e) sb.append(ch) else sb.append("\\u%04x".format(ch.code))
        }
        return if (sb.length > MAX_USER_COMMENT) truncate(o) else sb.toString()
    }

    /** If too big for EXIF, drop the weakest BLE/Wi‑Fi entries until it fits. */
    private fun truncate(o: JSONObject): String {
        val copy = JSONObject(o.toString())
        var s: String
        while (true) {
            s = asciiJsonNoTrunc(copy)
            if (s.length <= MAX_USER_COMMENT) break
            val ble = copy.optJSONObject("ble")?.optJSONArray("devices")
            val wifi = copy.optJSONObject("wifi")?.optJSONArray("aps")
            when {
                ble != null && ble.length() > 0 -> ble.remove(ble.length() - 1)
                wifi != null && wifi.length() > 0 -> wifi.remove(wifi.length() - 1)
                else -> break
            }
            copy.put("truncated", true)
        }
        return s
    }

    private fun asciiJsonNoTrunc(o: JSONObject): String {
        val s = o.toString()
        val sb = StringBuilder(s.length + 64)
        for (ch in s) if (ch.code in 0x20..0x7e) sb.append(ch) else sb.append("\\u%04x".format(ch.code))
        return sb.toString()
    }

    companion object {
        const val MAX_USER_COMMENT = 60_000

        /** Convert android.media.Image (YUV_420_888) to NV21 byte array, honouring strides. */
        fun yuv420ToNv21(image: Image): ByteArray {
            val w = image.width
            val h = image.height
            val ySize = w * h
            val out = ByteArray(ySize + 2 * ((w + 1) / 2) * ((h + 1) / 2))
            val planes = image.planes

            // Y
            val yBuf = planes[0].buffer
            val yRowStride = planes[0].rowStride
            val yPixStride = planes[0].pixelStride
            var pos = 0
            if (yPixStride == 1 && yRowStride == w) {
                yBuf.position(0); yBuf.get(out, 0, ySize); pos = ySize
            } else {
                val row = ByteArray(yRowStride)
                for (r in 0 until h) {
                    yBuf.position(r * yRowStride)
                    val len = minOf(yRowStride, yBuf.remaining())
                    yBuf.get(row, 0, len)
                    if (yPixStride == 1) {
                        System.arraycopy(row, 0, out, pos, w); pos += w
                    } else {
                        for (c in 0 until w) out[pos++] = row[c * yPixStride]
                    }
                }
            }

            // VU interleaved (NV21 = Y + V U V U ...)
            val uBuf = planes[1].buffer
            val vBuf = planes[2].buffer
            val cRowStride = planes[1].rowStride
            val cPixStride = planes[1].pixelStride
            val cw = (w + 1) / 2
            val chh = (h + 1) / 2
            val uRow = ByteArray(cRowStride)
            val vRow = ByteArray(cRowStride)
            for (r in 0 until chh) {
                uBuf.position(minOf(r * cRowStride, uBuf.limit()))
                vBuf.position(minOf(r * cRowStride, vBuf.limit()))
                val ul = minOf(cRowStride, uBuf.remaining())
                val vl = minOf(cRowStride, vBuf.remaining())
                uBuf.get(uRow, 0, ul)
                vBuf.get(vRow, 0, vl)
                for (c in 0 until cw) {
                    val idx = c * cPixStride
                    out[pos++] = if (idx < vl) vRow[idx] else 0
                    out[pos++] = if (idx < ul) uRow[idx] else 0
                }
            }
            return out
        }
    }
}
