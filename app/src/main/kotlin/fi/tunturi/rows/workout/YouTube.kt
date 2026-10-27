package fi.tunturi.rows.workout

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * YouTube links resolved to their real streams (via NewPipeExtractor), so they play
 * in ExoPlayer like any other source — full 0.25-4x pace following, no ads, no
 * WebView — and can be saved offline by [VideoStore].
 *
 * Above 360p YouTube serves picture and sound as separate streams, so a resolved
 * video is a pair. Both are fragmented MP4 with a byte-range index, which is what
 * lets playback build a DASH manifest and seek without fetching the whole file.
 */
object YouTube {

    /** Enough for a tablet, and keeps a saved hour-long row around 1-2 GB. */
    private const val MAX_HEIGHT = 1080

    class Resolved(
        val title: String,
        val durationSec: Long,
        val video: VideoStream,
        /** Null when only the muxed 360p stream was usable: its sound is built in. */
        val audio: AudioStream?,
    )

    init {
        NewPipe.init(HttpDownloader)
    }

    suspend fun resolve(videoId: String): Resolved = withContext(Dispatchers.IO) {
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")

        // AVC in MP4: every device decodes it and MediaMuxer can write it back out.
        val video = info.videoOnlyStreams
            .filter { it.isProgressive() && it.format == MediaFormat.MPEG_4 && it.codec.orEmpty().startsWith("avc1") }
            .filter { it.height in 1..MAX_HEIGHT }
            .maxWithOrNull(compareBy({ it.height }, { it.fps }, { it.bitrate }))
        val audio = info.audioStreams
            .filter { it.isProgressive() && it.format == MediaFormat.M4A }
            // Dubbed videos carry several tracks; the original is the one the footage was shot with.
            .maxWithOrNull(compareBy({ it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }, { it.averageBitrate }))

        if (video != null && audio != null) {
            return@withContext Resolved(info.name, info.duration, video, audio)
        }
        val muxed = info.videoStreams
            .filter { it.isProgressive() && it.format == MediaFormat.MPEG_4 }
            .maxByOrNull { it.height }
            ?: throw IllegalStateException("no playable stream for this video")
        Resolved(info.name, info.duration, muxed, null)
    }

    /**
     * googlevideo only serves a stream to the client that asked for it, so requests
     * must carry that client's User-Agent (and the web clients need a YouTube origin).
     */
    fun headersFor(url: String): Map<String, String> = when {
        YoutubeParsingHelper.isAndroidStreamingUrl(url) ->
            mapOf("User-Agent" to YoutubeParsingHelper.getAndroidUserAgent(null))
        YoutubeParsingHelper.isIosStreamingUrl(url) ->
            mapOf("User-Agent" to YoutubeParsingHelper.getIosUserAgent(null))
        YoutubeParsingHelper.isVisionOsStreamingUrl(url) ->
            mapOf("User-Agent" to YoutubeParsingHelper.getVisionOsUserAgent(null))
        YoutubeParsingHelper.isWebStreamingUrl(url) ||
            YoutubeParsingHelper.isWebEmbeddedPlayerStreamingUrl(url) ->
            mapOf("User-Agent" to BROWSER_UA, "Origin" to "https://www.youtube.com", "Referer" to "https://www.youtube.com/")
        else -> emptyMap()
    }

    private val dataSourceFactory: DataSource.Factory = ResolvingDataSource.Factory(
        DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true),
    ) { spec -> spec.withAdditionalHeaders(headersFor(spec.uri.toString())) }

    fun mediaSource(resolved: Resolved): MediaSource {
        val video = streamSource(resolved.video, resolved.durationSec)
        val audio = resolved.audio?.let { streamSource(it, resolved.durationSec) } ?: return video
        return MergingMediaSource(/* adjustPeriodTimeOffsets= */ true, /* clipDurations= */ true, video, audio)
    }

    private fun streamSource(stream: Stream, durationSec: Long): MediaSource {
        val itag = when (stream) {
            is VideoStream -> stream.itagItem
            is AudioStream -> stream.itagItem
            else -> null
        }
        // The muxed 360p stream has no index ranges, so it can only play as a plain file.
        if (itag == null || itag.indexEnd <= 0) {
            return ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(stream.content))
        }
        val mpd = YoutubeProgressiveDashManifestCreator.fromProgressiveStreamingUrl(stream.content, itag, durationSec)
        val manifest = DashManifestParser().parse(Uri.parse(stream.content), mpd.byteInputStream())
        val item = MediaItem.Builder().setUri(stream.content).setMimeType(MimeTypes.APPLICATION_MPD).build()
        return DashMediaSource.Factory(dataSourceFactory).createMediaSource(manifest, item)
    }

    // OTF/live DASH streams need segment-by-segment handling; plain ones are all we use.
    private fun Stream.isProgressive() = deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && isUrl

    private const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    /** The extractor's HTTP: YouTube's API calls, not the media itself. */
    private object HttpDownloader : Downloader() {
        override fun execute(request: Request): Response {
            val connection = (URL(request.url()).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                requestMethod = request.httpMethod()
                setRequestProperty("User-Agent", BROWSER_UA)
                request.headers().forEach { (name, values) ->
                    values.forEachIndexed { i, value ->
                        if (i == 0) setRequestProperty(name, value) else addRequestProperty(name, value)
                    }
                }
            }
            try {
                request.dataToSend()?.let { body ->
                    connection.doOutput = true
                    connection.outputStream.use { it.write(body) }
                }
                val code = connection.responseCode
                if (code == 429) throw ReCaptchaException("YouTube is rate limiting this device", request.url())
                val body = (if (code >= 400) connection.errorStream else connection.inputStream)
                    ?.use { it.readBytes().decodeToString() }
                val headers = connection.headerFields.filterKeys { it != null }
                return Response(code, connection.responseMessage, headers, body, connection.url.toString())
            } finally {
                connection.disconnect()
            }
        }
    }
}
