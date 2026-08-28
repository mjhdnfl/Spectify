/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.net.ConnectivityManager
import android.net.Uri
import android.util.Log
import androidx.media3.common.PlaybackException
import com.metrolist.innertube.NewPipeExtractor
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YouTubeClient
import com.metrolist.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_43_32
import com.metrolist.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_65_10
import com.metrolist.innertube.models.YouTubeClient.Companion.IOS
import com.metrolist.innertube.models.YouTubeClient.Companion.IPADOS
import com.metrolist.innertube.models.YouTubeClient.Companion.TVHTML5
import com.metrolist.innertube.models.YouTubeClient.Companion.VISIONOS
import com.metrolist.innertube.models.YouTubeClient.Companion.WEB_CREATOR
import com.metrolist.innertube.models.YouTubeClient.Companion.WEB_REMIX
import com.metrolist.innertube.models.response.PlayerResponse
import com.metrolist.music.constants.AudioQuality
import com.metrolist.music.utils.cipher.CipherDeobfuscator
import com.metrolist.music.utils.YTPlayerUtils.MAIN_CLIENT
import com.metrolist.music.utils.YTPlayerUtils.STREAM_FALLBACK_CLIENTS
import com.metrolist.music.utils.YTPlayerUtils.validateStatus
import com.metrolist.music.utils.potoken.PoTokenGenerator
import com.metrolist.music.utils.potoken.PoTokenResult
import com.metrolist.music.utils.sabr.EjsNTransformSolver
import okhttp3.OkHttpClient
import timber.log.Timber
import java.util.concurrent.TimeUnit

object YTPlayerUtils {
    private const val logTag = "YTPlayerUtils"
    private const val TAG = "YTPlayerUtils"

    private val httpClient = OkHttpClient.Builder()
        .proxy(YouTube.proxy)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val poTokenGenerator = PoTokenGenerator()

    /**
     * Size of the first media chunk ExoPlayer requests. Must stay in sync with
     * `MusicService.CHUNK_LENGTH`; kept as a local copy so this object does not have to depend
     * on the playback service. Used by [validateStatus] so the probe and the real request match.
     */
    private const val VALIDATION_CHUNK_LENGTH = 512 * 1024L

    /**
     * Compact, redacted description of a googlevideo stream URL, for logging.
     *
     * User-supplied logcats for the 403 / IO_UNSPECIFIED reports contained **no stream URL at
     * all** — zero occurrences of `googlevideo`, `expire=`, `itag=` or `pot=` across 32k lines —
     * so every conclusion had to be inferred from mime and bitrate. These are the parameters that
     * actually decide whether the CDN serves the stream. Signature material (`sig`, `signature`,
     * `sparams`) is never printed, and `pot`/`n` are reduced to presence and length.
     */
    private fun describeStreamUrl(url: String): String =
        try {
            val uri = Uri.parse(url)
            val expire = uri.getQueryParameter("expire")?.toLongOrNull()
            val nowSec = System.currentTimeMillis() / 1000
            buildString {
                append("host=").append(uri.host ?: "?")
                append(" itag=").append(uri.getQueryParameter("itag") ?: "-")
                append(" mime=").append(uri.getQueryParameter("mime") ?: "-")
                append(" c=").append(uri.getQueryParameter("c") ?: "-")
                append(" expire=").append(expire ?: "-")
                if (expire != null) append("(in ").append(expire - nowSec).append("s)")
                append(" hasPot=").append(uri.getQueryParameter("pot") != null)
                append(" nLen=").append(uri.getQueryParameter("n")?.length ?: -1)
                append(" cpn=").append(uri.getQueryParameter("cpn") ?: "-")
                append(" lmt=").append(uri.getQueryParameter("lmt") ?: "-")
                append(" sabr=").append(uri.getQueryParameter("sabr") ?: "-")
                append(" clen=").append(uri.getQueryParameter("clen") ?: "-")
            }
        } catch (e: Exception) {
            "unparseable url (${e.javaClass.simpleName})"
        }

    /**
     * Everything about a `/player` response that decides whether it can produce a playable stream.
     *
     * `urls`/`ciphers`/`bare` is the key triple: a format carrying neither a `url` nor a
     * `signatureCipher` means YouTube served a **SABR-only** response for that client, which this
     * app cannot use at all. Distinguishing that from "no suitable format" and from "playability
     * not OK" is the difference between three completely different bugs.
     */
    private fun describeResponse(client: YouTubeClient, response: PlayerResponse?): String =
        try {
            if (response == null) {
                Fix403.kv("client" to client.clientName, "response" to "NULL(requestFailed)")
            } else {
                val adaptive = response.streamingData?.adaptiveFormats.orEmpty()
                val audio = adaptive.filter { it.isAudio }
                Fix403.kv(
                    "client" to client.clientName,
                    "clientVersion" to client.clientVersion,
                    "status" to response.playabilityStatus.status,
                    "reason" to response.playabilityStatus.reason,
                    "hasStreamingData" to (response.streamingData != null),
                    "expiresInSeconds" to response.streamingData?.expiresInSeconds,
                    "adaptiveFormats" to adaptive.size,
                    "audioFormats" to audio.size,
                    "urls" to adaptive.count { !it.url.isNullOrEmpty() },
                    "ciphers" to adaptive.count { !it.signatureCipher.isNullOrEmpty() || !it.cipher.isNullOrEmpty() },
                    // Neither url nor cipher => SABR-only response, unusable by this app.
                    "bare" to adaptive.count {
                        it.url.isNullOrEmpty() && it.signatureCipher.isNullOrEmpty() && it.cipher.isNullOrEmpty()
                    },
                    "audioItags" to audio.joinToString("/") { it.itag.toString() }.ifEmpty { "-" },
                    "musicVideoType" to response.videoDetails?.musicVideoType,
                    "title" to response.videoDetails?.title,
                )
            }
        } catch (e: Exception) {
            "describeResponse failed (${e.javaClass.simpleName}: ${e.message})"
        }

    private val MAIN_CLIENT: YouTubeClient = WEB_REMIX

    /**
     * Ordered by *measured* ability to serve a whole file, not by theory.
     *
     * The decisive measurement: an IOS/IPADOS/ANDROID_VR(old) stream URL is a **~1 MiB preview**.
     * googlevideo serves a fixed byte prefix and answers 403 to everything past it — an
     * offset-based cap, binary-searched to the byte and stable across independent resolves:
     *
     * ```
     * videoId       itag 251 last readable byte   = seconds of audio
     * Rr1Cdli5nE8   1040807                         ~61 of 268
     * phLb_SoPBlA   1049091                         ~61 of 274
     * UbX5Yns8fHk   1019638                         ~67 of 159
     * ```
     *
     * It is not request-count, not rate, not expiry: a fresh URL asked for `bytes=524288-1048575`
     * as its *first* request already 403s, and a range straddling the cap is rejected wholesale.
     * Adding `cpn`/`rn`, the client's own User-Agent, Origin/Referer, `alr`, `ratebypass` or a
     * bogus `pot` changes nothing. This is what produced the field reports of playback dying after
     * 30-90 seconds — ExoPlayer reads ahead, so the 403 lands before the audible stall.
     *
     * VISIONOS is the exception and the reason it now leads: probed on the same three videoIds it
     * read every file start to finish (2.4 / 4.5 / 4.7 MB, 100% 206), survived 51 ranged reads
     * paced over 300 s, and returned 206 for the exact byte offsets that 403 in production.
     *
     * IOS/IPADOS are kept at the tail deliberately — upstream deleted them, but a 1 MiB preview
     * still beats no stream at all if everything above fails. They must never be reached while a
     * client above them can serve the file.
     */
    private val STREAM_FALLBACK_CLIENTS: Array<YouTubeClient> = arrayOf(
        VISIONOS,                        // only client measured to serve a complete file
        ANDROID_VR_1_65_10,              // current yt-dlp/YouTube.js pin; whole-file capable
        TVHTML5,
        ANDROID_VR_1_43_32,              // version-gated; kept as the control against 1.65.10
        IPADOS,                          // ~1 MiB preview only — last resort
        IOS,                             // ~1 MiB preview only — last resort
        // The only client that answers OK for age-restricted / explicit tracks, because it is the
        // only authenticated one left in the chain. Its formats are always behind the signature
        // cipher, so it only works while PlayerConfigStore has a config for the live player.
        WEB_CREATOR
    )
    // TVHTML5_SIMPLY_EMBEDDED_PLAYER was here as the login-free age-restriction bypass. It is dead
    // server-side: measured on device across four consecutive cascades and again in isolated probes,
    // it answers `ERROR / "YouTube is no longer supported in this application or device"` every
    // time, with zero formats. Keeping it cost a round trip (~70 ms) on every resolution that had
    // to pass through it and could never succeed. The definition is left in YouTubeClient.

    /**
     * For normal content we skip the MAIN_CLIENT (WEB_REMIX) stream attempt and start at the top
     * of [STREAM_FALLBACK_CLIENTS]. WEB_REMIX returns formats behind YouTube's signature cipher /
     * n-challenge, which can no longer be solved client-side; that attempt lives at
     * `clientIndex == -1`, not inside the array, so skipping it means starting at index 0.
     * Metadata/history still come from the WEB_REMIX response fetched above.
     *
     * This used to be `indexOf(ANDROID_VR_1_43_32)`, which was index 2 under the old ordering.
     * Under the current ordering that expression resolves to **4**, silently skipping VISIONOS,
     * ANDROID_VR 1.65.10 and both TVHTML5 entries on every normal-content playback — i.e. it would
     * have quietly disabled the entire fix. Pinned to 0 and covered by a unit test.
     */
    private val NORMAL_CONTENT_STREAM_START_INDEX: Int = 0

    /**
     * Privately-owned (uploaded) tracks need TVHTML5. Resolved by identity rather than by a
     * hardcoded index, which under the previous ordering happened to be 1 and would now point at
     * [ANDROID_VR_1_65_10].
     */
    private val PRIVATE_TRACK_STREAM_START_INDEX: Int =
        STREAM_FALLBACK_CLIENTS.indexOf(TVHTML5).takeIf { it >= 0 } ?: 0

    data class PlaybackData(
        val audioConfig: PlayerResponse.PlayerConfig.AudioConfig?,
        val videoDetails: PlayerResponse.VideoDetails?,
        val playbackTracking: PlayerResponse.PlaybackTracking?,
        val format: PlayerResponse.StreamingData.Format,
        val streamUrl: String,
        val streamExpiresInSeconds: Int,
    )
    /**
     * Custom player response intended to use for playback.
     * Metadata like audioConfig and videoDetails are from [MAIN_CLIENT].
     * Format & stream can be from [MAIN_CLIENT] or [STREAM_FALLBACK_CLIENTS].
     */
    suspend fun playerResponseForPlayback(
        videoId: String,
        playlistId: String? = null,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
    ): Result<PlaybackData> = runCatching {
        val fx = Fix403.nextId("res")
        Timber.tag(TAG).d("=== PLAYER RESPONSE FOR PLAYBACK ===")
        Timber.tag(TAG).d("videoId: $videoId, playlistId: $playlistId, quality: $audioQuality")

        val isUploadedTrack = playlistId == "MLPT" || playlistId?.contains("MLPT") == true
        val isLoggedIn = YouTube.cookie != null

        Fix403.i(
            fx, "resolve.begin",
            Fix403.kv(
                "videoId" to videoId,
                "playlistId" to playlistId,
                "quality" to audioQuality,
                "uploaded" to isUploadedTrack,
                "loggedIn" to isLoggedIn,
            ),
        )

        // Get signature timestamp
        val signatureTimestamp = try {
            getSignatureTimestampOrNull(videoId)
        } catch (e: Exception) {
            Timber.tag("ResolverError").e(e, "STS resolution CRASHED for $videoId")
            Fix403.fail(fx, "sts.crash", e)
            SignatureTimestampResult(null, false)
        }
        
        Fix403.i(fx, "resolve.sts", Fix403.kv("sts" to signatureTimestamp.timestamp))

        // Generate PoToken
        var poToken: PoTokenResult? = null
        val sessionId = if (isLoggedIn) YouTube.dataSyncId else YouTube.visitorData
        if (MAIN_CLIENT.useWebPoTokens && sessionId != null) {
            try {
                poToken = poTokenGenerator.getWebClientPoToken(videoId, sessionId)
                Fix403.i(fx, "potoken.result", Fix403.kv("obtained" to (poToken != null)))
            } catch (e: Exception) {
                Timber.tag("ResolverError").e(e, "PoToken generation failed for $videoId")
                Fix403.fail(fx, "potoken.failed", e)
            }
        }

        // Try MAIN_CLIENT
        var mainPlayerResponse = try {
            YouTube.player(videoId, playlistId, MAIN_CLIENT, signatureTimestamp.timestamp, poToken?.playerRequestPoToken).getOrThrow()
        } catch (e: Exception) {
            Timber.tag("ResolverError").e(e, "MAIN_CLIENT request FAILED for $videoId")
            Fix403.fail(fx, "mainClient.failed", e)
            // If main client fails, we'll try to continue with fallback chain if possible
            null
        }

        if (mainPlayerResponse != null) {
            Fix403.i(fx, "mainClient.response", describeResponse(MAIN_CLIENT, mainPlayerResponse))
        }

        var usedAgeRestrictedClient: YouTubeClient? = null
        val wasOriginallyAgeRestricted: Boolean

        // Check if WEB_REMIX response indicates age-restricted
        val mainStatus = mainPlayerResponse?.playabilityStatus?.status
        val isAgeRestrictedFromResponse = mainStatus in listOf("AGE_CHECK_REQUIRED", "AGE_VERIFICATION_REQUIRED", "LOGIN_REQUIRED", "CONTENT_CHECK_REQUIRED")
        wasOriginallyAgeRestricted = isAgeRestrictedFromResponse

        if (isAgeRestrictedFromResponse && isLoggedIn) {
            Timber.tag(TAG).i("Age-restricted detected, trying WEB_CREATOR for $videoId")
            val creatorResponse = YouTube.player(videoId, playlistId, WEB_CREATOR, null, null).getOrNull()
            if (creatorResponse?.playabilityStatus?.status == "OK") {
                mainPlayerResponse = creatorResponse
                usedAgeRestrictedClient = WEB_CREATOR
                Fix403.i(fx, "ageRestricted.bypass", "via WEB_CREATOR")
            }
        }

        if (mainPlayerResponse == null && !isAgeRestrictedFromResponse) {
            throw Exception("Initial player response failed (MAIN_CLIENT)")
        }

        // Determine starting index for fallback cascade
        val isPrivateTrack = mainPlayerResponse?.videoDetails?.musicVideoType == "MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK"
        val skipMainClient = MAIN_CLIENT.useWebPoTokens && poToken == null
        
        val startIndex = when {
            isPrivateTrack -> PRIVATE_TRACK_STREAM_START_INDEX
            isAgeRestrictedFromResponse -> 0
            skipMainClient -> 0
            else -> NORMAL_CONTENT_STREAM_START_INDEX
        }

        val cascade = mutableListOf<String>()
        var format: PlayerResponse.StreamingData.Format? = null
        var streamUrl: String? = null
        var streamExpiresInSeconds: Int? = null

        for (clientIndex in (startIndex until STREAM_FALLBACK_CLIENTS.size)) {
            val client = STREAM_FALLBACK_CLIENTS[clientIndex]
            
            if (client.loginRequired && !isLoggedIn) {
                cascade += "${client.clientName}=SKIP(auth)"
                continue
            }

            val clientPoToken = if (client.useWebPoTokens) poToken?.playerRequestPoToken else null
            val clientSigTimestamp = if (wasOriginallyAgeRestricted) null else signatureTimestamp.timestamp
            
            val streamPlayerResponse = try {
                YouTube.player(videoId, playlistId, client, clientSigTimestamp, clientPoToken).getOrNull()
            } catch (e: Exception) {
                Timber.tag("ResolverError").w(e, "Fallback client ${client.clientName} CRASHED for $videoId")
                Fix403.fail(fx, "client.crash.${client.clientName}", e)
                null
            }

            if (streamPlayerResponse?.playabilityStatus?.status == "OK") {
                val responseToUse = if (wasOriginallyAgeRestricted) streamPlayerResponse 
                                    else (YouTube.newPipePlayer(videoId, streamPlayerResponse) ?: streamPlayerResponse)

                format = findFormat(responseToUse, audioQuality, connectivityManager)
                if (format != null) {
                    streamUrl = findUrlOrNull(format, videoId, responseToUse, skipNewPipe = wasOriginallyAgeRestricted)
                    if (streamUrl != null) {
                        // Apply n-transform
                        if (client.useWebPoTokens || isPrivateTrack) {
                            streamUrl = try {
                                CipherDeobfuscator.transformNParamInUrl(streamUrl)
                            } catch (e: Exception) {
                                Timber.tag("ResolverError").e(e, "N-transform failed for $videoId")
                                streamUrl
                            }
                        }
                        
                        streamExpiresInSeconds = responseToUse.streamingData?.expiresInSeconds
                        
                        // Validate stream
                        if (clientIndex == STREAM_FALLBACK_CLIENTS.size - 1 || isPrivateTrack || 
                            validateStatus(streamUrl, format.contentLength, "client=${client.clientName}")) {
                            
                            Fix403.i(fx, "resolve.success", Fix403.kv("client" to client.clientName, "itag" to format.itag))
                            return@runCatching PlaybackData(
                                mainPlayerResponse?.playerConfig?.audioConfig ?: streamPlayerResponse.playerConfig?.audioConfig,
                                mainPlayerResponse?.videoDetails ?: streamPlayerResponse.videoDetails,
                                mainPlayerResponse?.playbackTracking ?: streamPlayerResponse.playbackTracking,
                                format,
                                streamUrl,
                                streamExpiresInSeconds ?: 0
                            )
                        } else {
                            cascade += "${client.clientName}=REJECTED(probe)"
                        }
                    } else {
                        cascade += "${client.clientName}=NO_URL"
                    }
                } else {
                    cascade += "${client.clientName}=NO_FORMAT"
                }
            } else {
                val status = streamPlayerResponse?.playabilityStatus?.status ?: "NULL"
                cascade += "${client.clientName}=NOT_OK($status)"
            }
        }

        val cascadeStr = cascade.joinToString(" | ")
        Fix403.e(fx, "resolve.failed", "cascade=$cascadeStr")
        throw Exception("Playback Failed: All resolution clients failed ($cascadeStr)")
    }.onFailure { e ->
        Timber.tag("ResolverError").e(e, "CRITICAL resolution failure for $videoId")
        Fix403.fail(Fix403.nextId("err"), "resolve.exception", e, Fix403.kv("videoId" to videoId))
    }
    /**
     * Simple player response intended to use for metadata only.
     * Stream URLs of this response might not work so don't use them.
     */
    suspend fun playerResponseForMetadata(
        videoId: String,
        playlistId: String? = null,
    ): Result<PlayerResponse> {
        Timber.tag(logTag).d("Fetching metadata-only player response for videoId: $videoId using MAIN_CLIENT: ${MAIN_CLIENT.clientName}")
        return YouTube.player(videoId, playlistId, client = WEB_REMIX) // ANDROID_VR does not work with history
            .onSuccess { Timber.tag(logTag).d("Successfully fetched metadata") }
            .onFailure { Timber.tag(logTag).e(it, "Failed to fetch metadata") }
    }

    private fun findFormat(
        playerResponse: PlayerResponse,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
    ): PlayerResponse.StreamingData.Format? {
        Timber.tag(logTag).d("Finding format with audioQuality: $audioQuality, network metered: ${connectivityManager.isActiveNetworkMetered}")

        val format = playerResponse.streamingData?.adaptiveFormats
            ?.filter { it.isAudio && it.isOriginal }
            ?.maxByOrNull {
                it.bitrate * when (audioQuality) {
                    AudioQuality.AUTO -> if (connectivityManager.isActiveNetworkMetered) -1 else 1
                    AudioQuality.HIGH -> 1
                    AudioQuality.LOW -> -1
                } + (if (it.mimeType.startsWith("audio/webm")) 10240 else 0) // prefer opus stream
            }

        if (format != null) {
            Timber.tag(logTag).d("Selected format: ${format.mimeType}, bitrate: ${format.bitrate}")
        } else {
            Timber.tag(logTag).d("No suitable audio format found")
        }

        return format
    }
    /**
     * Checks if the stream url returns a successful status.
     *
     * **The probe must mirror the request ExoPlayer actually issues.** `MusicService`'s resolver
     * ends with `.subrange(0, CHUNK_LENGTH)`, so the first media request is always
     * `Range: bytes=0-524287`. A Range-*less* probe is a different request, and googlevideo
     * answers it with **403 on every YouTube Music art track** (`- Topic` uploads — i.e. nearly
     * everything this app plays) while serving the ranged GET with 206. Measured:
     *
     * ```
     * videoId       client   HEAD(noRange)  HEAD(Range0-512k)  GET(Range0-512k)
     * Rr1Cdli5nE8   IPADOS   403            206                206   "Like That"
     * phLb_SoPBlA   IPADOS   403            206                206   "Not Like Us"
     * dQw4w9WgXcQ   IPADOS   200            206                206   ordinary video
     * ```
     *
     * Ordinary videos answer 200, which is why a Range-less probe looks fine in casual testing
     * and fails systematically in production. Sending the Range makes a 403 here mean the stream
     * really is forbidden, which in turn makes rejecting it safe.
     *
     * Rules here:
     *  - 2xx (200/206) → valid
     *  - 405 → treat as valid (HEAD method refused outright; ExoPlayer will GET)
     *  - 403/410 → invalid; move on to the next fallback client
     *  - IOException (timeout/reset) → treat as valid; ExoPlayer has its own retry and
     *    killing the client here just cascades us down the fallback chain for no reason
     *  - other HTTP codes (4xx/5xx) → invalid
     *
     * **The probe must also reach past the preview window.** IOS/IPADOS/old-ANDROID_VR URLs are
     * served only for a fixed prefix of roughly 1 MiB and 403 beyond it (see the
     * [STREAM_FALLBACK_CLIENTS] KDoc for the measured per-video caps). A probe that only asks for
     * `bytes=0-524287` sits entirely inside that window, so it returns 206 for a stream that is
     * guaranteed to die around 60 seconds in — it accepts precisely the URLs we need to reject.
     *
     * So when the format's `contentLength` is known we probe the **last byte of the file**
     * instead: a URL that will serve its final byte is not a truncated preview, and the check is
     * independent of where any particular cap happens to fall. We fall back to the first-chunk
     * probe only when `contentLength` is absent.
     *
     * Remaining known discrepancy: we send `Cookie` here and ExoPlayer does not.
     */
    private fun validateStatus(url: String, contentLength: Long? = null, label: String = ""): Boolean {
        Timber.tag(logTag).d("Validating stream URL status")
        try {
            // Last byte when we know the size, else the first chunk ExoPlayer will ask for.
            val range = if (contentLength != null && contentLength > 0) {
                "bytes=${contentLength - 1}-${contentLength - 1}"
            } else {
                "bytes=0-${VALIDATION_CHUNK_LENGTH - 1}"
            }
            val requestBuilder = okhttp3.Request.Builder()
                .head()
                .url(url)
                .addHeader("Range", range)

            YouTube.cookie?.let { cookie ->
                requestBuilder.addHeader("Cookie", cookie)
            }

            val response = httpClient.newCall(requestBuilder.build()).execute()
            response.close()
            val code = response.code
            val accepted = response.isSuccessful || code == 405
            when {
                !accepted ->
                    Timber.tag(logTag).w("Stream URL REJECTED: code=$code range=$range $label ${describeStreamUrl(url)}")
                !response.isSuccessful ->
                    Timber.tag(logTag).w("Stream URL accepted on non-2xx code=$code (HEAD refused) range=$range $label")
                else ->
                    Timber.tag(logTag).d("Stream URL validation: code=$code range=$range accepted $label")
            }
            return accepted
        } catch (e: java.io.IOException) {
            // Network timeout / reset while HEAD-probing. The stream URL itself may still
            // be fine — let ExoPlayer attempt GET rather than burning a fallback client.
            Timber.tag(logTag).w(e, "Stream URL HEAD probe failed (IO); accepting optimistically")
            return true
        } catch (e: Exception) {
            Timber.tag(logTag).e(e, "Stream URL validation failed with exception")
            reportException(e)
        }
        return false
    }
    data class SignatureTimestampResult(
        val timestamp: Int?,
        val isAgeRestricted: Boolean
    )

    private fun getSignatureTimestampOrNull(videoId: String): SignatureTimestampResult {
        Timber.tag(logTag).d("Getting signature timestamp for videoId: $videoId")
        val result = NewPipeExtractor.getSignatureTimestamp(videoId)
        return result.fold(
            onSuccess = { timestamp ->
                Timber.tag(logTag).d("Signature timestamp obtained: $timestamp")
                SignatureTimestampResult(timestamp, isAgeRestricted = false)
            },
            onFailure = { error ->
                val isAgeRestricted = error.message?.contains("age-restricted", ignoreCase = true) == true ||
                    error.cause?.message?.contains("age-restricted", ignoreCase = true) == true
                if (isAgeRestricted) {
                    Timber.tag(logTag).d("Age-restricted content detected from NewPipe")
                    Timber.tag(TAG).i("Age-restricted detected early via NewPipe: videoId=$videoId")
                } else {
                    Timber.tag(logTag).e(error, "Failed to get signature timestamp")
                    reportException(error)
                }
                SignatureTimestampResult(null, isAgeRestricted)
            }
        )
    }

    private suspend fun findUrlOrNull(
        format: PlayerResponse.StreamingData.Format,
        videoId: String,
        playerResponse: PlayerResponse,
        skipNewPipe: Boolean = false
    ): String? {
        Timber.tag(logTag).d("Finding stream URL for format: ${format.mimeType}, videoId: $videoId, skipNewPipe: $skipNewPipe")

        // First check if format already has a URL
        if (!format.url.isNullOrEmpty()) {
            Timber.tag(logTag).d("Using URL from format directly")
            return format.url
        }

        // Try custom cipher deobfuscation for signatureCipher formats
        val signatureCipher = format.signatureCipher ?: format.cipher
        if (!signatureCipher.isNullOrEmpty()) {
            Timber.tag(logTag).d("Format has signatureCipher, using custom deobfuscation")
            val customDeobfuscatedUrl = CipherDeobfuscator.deobfuscateStreamUrl(signatureCipher, videoId)
            if (customDeobfuscatedUrl != null) {
                Timber.tag(logTag).d("Stream URL obtained via custom cipher deobfuscation")
                return customDeobfuscatedUrl
            }
            Timber.tag(logTag).d("Custom cipher deobfuscation failed")
        }

        // Skip NewPipe for age-restricted content
        if (skipNewPipe) {
            Timber.tag(logTag).d("Skipping NewPipe methods for age-restricted content")
            return null
        }

        // Try to get URL using NewPipeExtractor signature deobfuscation
        val deobfuscatedUrl = NewPipeExtractor.getStreamUrl(format, videoId)
        if (deobfuscatedUrl != null) {
            Timber.tag(logTag).d("Stream URL obtained via NewPipe deobfuscation")
            return deobfuscatedUrl
        }

        // Fallback: try to get URL from StreamInfo
        Timber.tag(logTag).d("Trying StreamInfo fallback for URL")
        val streamUrls = YouTube.getNewPipeStreamUrls(videoId)
        if (streamUrls.isNotEmpty()) {
            val streamUrl = streamUrls.find { it.first == format.itag }?.second
            if (streamUrl != null) {
                Timber.tag(logTag).d("Stream URL obtained from StreamInfo")
                return streamUrl
            }

            // If exact itag not found, try to find any audio stream
            val audioStream = streamUrls.find { urlPair ->
                playerResponse.streamingData?.adaptiveFormats?.any {
                    it.itag == urlPair.first && it.isAudio
                } == true
            }?.second

            if (audioStream != null) {
                Timber.tag(logTag).d("Audio stream URL obtained from StreamInfo (different itag)")
                return audioStream
            }
        }

        Timber.tag(logTag).e("Failed to get stream URL")
        return null
    }

    fun forceRefreshForVideo(videoId: String) {
        Timber.tag(logTag).d("Force refreshing for videoId: $videoId")
    }
}
