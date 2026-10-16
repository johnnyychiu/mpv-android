package `is`.xyz.mpv.youtube

import `is`.xyz.mpv.BuildConfig
import android.content.Context
import android.util.AtomicFile
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.R as YoutubeDlResources
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.GregorianCalendar
import java.util.TimeZone

/** Installs the verified minimum extractor after the Android wrapper initializes its runtime. */
internal object BundledYouTubeTools {
    @Synchronized
    fun installIfNeeded(context: Context) {
        val appContext = context.applicationContext
        val runtime = File(File(File(appContext.noBackupFilesDir, YoutubeDL.baseName), YoutubeDL.ytdlpDirName), YoutubeDL.ytdlpBin)
        val storage = AtomicFile(runtime)
        // openRead also recovers an interrupted AtomicFile replacement before deciding.
        val existing = try { storage.openRead().use(::fingerprint) } catch (_: FileNotFoundException) { null }
        val matchesBundle = existing?.let { it.size == BuildConfig.YT_DLP_BUNDLED_SIZE && it.sha256 == BuildConfig.YT_DLP_BUNDLED_SHA256 } == true
        // Wrapper preferences may be restored while its no-backup binary is not.
        // init() then recreates the old raw resource despite a newer version name.
        val matchesLegacyRuntime = existing != null && !matchesBundle &&
            appContext.resources.openRawResource(YoutubeDlResources.raw.ytdlp).use(::fingerprint) == existing
        val install = BundledYouTubeToolsPolicy.shouldInstall(
            runtimeExists = existing != null && existing.size > 0,
            matchesBundle = matchesBundle,
            installedVersions = listOf(YoutubeDL.versionName(appContext), YoutubeDL.version(appContext)),
            minimumVersion = BuildConfig.YT_DLP_BUNDLED_VERSION,
            matchesLegacyRuntime = matchesLegacyRuntime
        )
        if (!install) {
            if (matchesBundle) rememberVerifiedBundle(appContext)
            return
        }

        val output = storage.startWrite()
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            appContext.assets.open("yt-dlp/yt-dlp").use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    if (size > BuildConfig.YT_DLP_BUNDLED_SIZE) throw IOException("The bundled YouTube tools have an invalid size.")
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
            }
            if (size != BuildConfig.YT_DLP_BUNDLED_SIZE || hex(digest.digest()) != BuildConfig.YT_DLP_BUNDLED_SHA256) {
                throw IOException("The bundled YouTube tools failed verification.")
            }
            output.fd.sync()
            storage.finishWrite(output)
            val installed = storage.openRead().use(::fingerprint)
            if (installed.size != BuildConfig.YT_DLP_BUNDLED_SIZE || installed.sha256 != BuildConfig.YT_DLP_BUNDLED_SHA256) {
                throw IOException("The bundled YouTube tools could not be installed.")
            }
        } catch (error: Exception) {
            storage.failWrite(output)
            throw IOException("Could not prepare the bundled YouTube tools.", error)
        }
        rememberVerifiedBundle(appContext)
    }

    private fun rememberVerifiedBundle(context: Context) {
        val preferences = context.getSharedPreferences("youtube_bundled_tools", Context.MODE_PRIVATE)
        if (preferences.getString("version", null) == BuildConfig.YT_DLP_BUNDLED_VERSION &&
            preferences.getString("sha256", null) == BuildConfig.YT_DLP_BUNDLED_SHA256) return
        if (!preferences.edit().putString("version", BuildConfig.YT_DLP_BUNDLED_VERSION)
                .putString("sha256", BuildConfig.YT_DLP_BUNDLED_SHA256).commit()) {
            throw IOException("The bundled YouTube tools installation could not be recorded.")
        }
    }

    private data class Fingerprint(val size: Long, val sha256: String)

    private fun fingerprint(input: InputStream): Fingerprint {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        var size = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            size += count
            digest.update(buffer, 0, count)
        }
        return Fingerprint(size, hex(digest.digest()))
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/** No Android dependency: unknown metadata is not evidence that a runtime is current. */
internal object BundledYouTubeToolsPolicy {
    private val releaseDate = Regex("(?:^|[^0-9])([0-9]{4})\\.([0-9]{2})\\.([0-9]{2})(?=$|[^0-9])")

    fun shouldInstall(
        runtimeExists: Boolean,
        matchesBundle: Boolean,
        installedVersions: List<String?>,
        minimumVersion: String,
        matchesLegacyRuntime: Boolean = false
    ): Boolean {
        if (!runtimeExists) return true
        if (matchesBundle) return false
        if (matchesLegacyRuntime) return true
        val minimum = date(minimumVersion) ?: throw IllegalArgumentException("The bundled tools version is invalid.")
        return installedVersions.none { version -> date(version)?.let { it >= minimum } == true }
    }

    private fun date(version: String?): Int? {
        val parts = version?.take(200)?.let(releaseDate::find)?.groupValues ?: return null
        val year = parts[1].toInt()
        val month = parts[2].toInt()
        val day = parts[3].toInt()
        if (year !in 2000..9999) return null
        return try {
            GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
                isLenient = false
                clear()
                set(year, month - 1, day)
                timeInMillis
            }
            year * 10000 + month * 100 + day
        } catch (_: IllegalArgumentException) { null }
    }
}
