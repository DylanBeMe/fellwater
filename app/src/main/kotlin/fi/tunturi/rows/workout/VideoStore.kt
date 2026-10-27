package fi.tunturi.rows.workout

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer

/**
 * Offline copies of videos: any direct file URL (mp4/webm/…), or a YouTube video.
 * Saved into app storage so a row works with no network.
 *
 * Segmented formats (HLS/DASH) are not supported: they need a segment-aware
 * downloader. YouTube works because its streams are plain files underneath.
 */
class VideoStore(private val context: Context) {

    val dir: File
        get() = File(context.filesDir, "videos").apply { mkdirs() }

    fun list(): List<File> =
        dir.listFiles { f -> f.isFile && f.length() > 0 && !f.name.endsWith(PART) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    fun delete(file: File) {
        runCatching { file.delete() }
    }

    suspend fun download(url: String, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                requestMethod = "GET"
            }
            try {
                connection.connect()
                if (connection.responseCode !in 200..299) {
                    throw IllegalStateException("HTTP ${connection.responseCode}")
                }
                val total = connection.contentLengthLong
                val name = url.substringAfterLast('/').substringBefore('?').ifBlank { "video.mp4" }
                val target = newFile(name)

                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            if (total > 0) onProgress((copied.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
                if (target.length() == 0L) {
                    target.delete()
                    throw IllegalStateException("empty download")
                }
                onProgress(1f)
                target
            } finally {
                connection.disconnect()
            }
        }

    /**
     * Picture and sound are fetched separately (that is how YouTube serves anything
     * above 360p) and muxed into one mp4, so the saved copy plays like any local file.
     */
    suspend fun downloadYouTube(videoId: String, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val resolved = YouTube.resolve(videoId)
            val parts = listOfNotNull(resolved.video.content, resolved.audio?.content)
            val sizes = listOfNotNull(resolved.video.itagItem?.contentLength, resolved.audio?.itagItem?.contentLength)
            // Sizes are only for the progress bar; 5% is left for muxing.
            val total = sizes.sum().takeIf { sizes.size == parts.size && it > 0 } ?: 0L
            var done = 0L

            val temps = parts.mapIndexed { i, url -> File(dir, "yt-$videoId-$i$PART") }
            val target = newFile("${resolved.title}.mp4")
            try {
                parts.zip(temps).forEach { (url, temp) ->
                    temp.outputStream().use { output ->
                        fetchInChunks(url, output) { bytes ->
                            done += bytes
                            if (total > 0) onProgress((done.toFloat() / total * 0.95f).coerceIn(0f, 0.95f))
                        }
                    }
                }
                ensureActive()
                if (temps.size == 1) {
                    if (!temps[0].renameTo(target)) throw IllegalStateException("could not save video")
                } else {
                    mux(temps, target)
                }
                onProgress(1f)
                target
            } catch (e: Throwable) {
                target.delete()
                throw e
            } finally {
                temps.forEach { it.delete() }
            }
        }

    /**
     * googlevideo throttles or cuts off one long request, so the file is fetched in
     * fixed byte ranges, each with the headers of the client the URL was issued to.
     */
    private suspend fun fetchInChunks(url: String, output: OutputStream, onBytes: (Long) -> Unit) {
        val headers = YouTube.headersFor(url)
        val buffer = ByteArray(64 * 1024)
        var start = 0L
        var length = Long.MAX_VALUE
        while (start < length) {
            currentCoroutineContext().ensureActive()
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
                setRequestProperty("Range", "bytes=$start-${start + CHUNK - 1}")
            }
            try {
                val code = connection.responseCode
                if (code !in 200..299) throw IllegalStateException("HTTP $code")
                if (code == 200 && start > 0) throw IllegalStateException("server does not support ranges")
                // "bytes 0-9999999/123456789"; a 200 means the server ignored the range.
                length = if (code == 206) {
                    connection.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull()
                        ?: throw IllegalStateException("no Content-Range")
                } else {
                    connection.contentLengthLong.takeIf { it > 0 } ?: throw IllegalStateException("unknown length")
                }
                connection.inputStream.use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        start += read
                        onBytes(read.toLong())
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    /** Copies each input's single track into one mp4, interleaved by timestamp. */
    private fun mux(inputs: List<File>, target: File) {
        val muxer = MediaMuxer(target.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val extractors = inputs.map { file -> MediaExtractor().apply { setDataSource(file.path) } }
        try {
            val tracks = extractors.map { extractor ->
                extractor.selectTrack(0)
                muxer.addTrack(extractor.getTrackFormat(0))
            }
            muxer.start()
            val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            val live = extractors.indices.toMutableSet()
            while (live.isNotEmpty()) {
                val i = live.minBy { extractors[it].sampleTime }
                val extractor = extractors[i]
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) {
                    live.remove(i)
                    continue
                }
                val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else {
                    0
                }
                info.set(0, size, extractor.sampleTime, flags)
                muxer.writeSampleData(tracks[i], buffer, info)
                extractor.advance()
            }
            muxer.stop()
        } finally {
            extractors.forEach { it.release() }
            muxer.release()
        }
    }

    /** Long titles are cut to 60 characters, keeping the extension. */
    private fun newFile(name: String): File {
        val unsafe = Regex("[^A-Za-z0-9._-]")
        val ext = name.substringAfterLast('.', "").replace(unsafe, "").take(5)
        val base = (if (ext.isEmpty()) name else name.substringBeforeLast('.')).replace(unsafe, "_").take(60)
        return File(dir, "${System.currentTimeMillis()}-$base" + if (ext.isEmpty()) "" else ".$ext")
    }

    private companion object {
        const val CHUNK = 10L * 1024 * 1024
        const val PART = ".part"
    }
}
