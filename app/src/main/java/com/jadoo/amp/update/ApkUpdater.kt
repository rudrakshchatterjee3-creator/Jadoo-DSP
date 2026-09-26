package com.jadoo.amp.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Lane B of the update system: installs a new APK from within the app.
 *
 * This is still a package install — Android has no mechanism for replacing an
 * app's compiled code without one, and anything claiming otherwise is either
 * Play's own update flow or a policy violation. What Lane B removes is the
 * *friction*: no browser, no Downloads folder, no file manager, no manual
 * "install anyway" hunt. Because the new APK is signed with the same key
 * (`jadoo.jks`), Android treats it as an in-place update and every setting,
 * profile and DataStore entry survives.
 *
 * ## Security
 *
 * The downloaded file is code that will run with this app's identity and data.
 * [verifyApk] therefore runs BEFORE any install is attempted and refuses the
 * file unless:
 *
 *  - it parses as an APK at all,
 *  - its package name matches this app's, and
 *  - its signing certificate matches this app's byte-for-byte.
 *
 * Without that last check, anything that can influence the download URL — a
 * compromised release, a hijacked CDN, a hostile proxy — would get arbitrary
 * code installed as a trusted update. The check is cheap and non-negotiable;
 * it is deliberately not behind a flag.
 */
object ApkUpdater {

    private const val TAG = "ApkUpdater"
    private const val INSTALL_ACTION = "com.jadoo.amp.ACTION_INSTALL_STATUS"

    /** Progress of an in-app update, surfaced to the UI. */
    sealed class State {
        data object Idle : State()
        data class Downloading(val progress: Float) : State()
        data object Verifying : State()
        data object Installing : State()
        data class Failed(val reason: String) : State()
    }

    /**
     * True when the OS will let this app install packages. On API 26+ this is
     * a per-app user grant ("Install unknown apps"); [requestInstallPermission]
     * opens the right settings page for it.
     */
    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun requestInstallPermission(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not open unknown-sources settings: ${e.message}")
        }
    }

    /**
     * Downloads [release]'s APK asset to app-private cache, reporting
     * 0f..1f through [onProgress]. Returns null on any failure.
     *
     * App-private cache, not external storage: the file is code awaiting
     * verification, and nothing else on the device should be able to swap it
     * out between the download finishing and the signature check running.
     */
    suspend fun download(
        context: Context,
        release: ReleaseInfo,
        onProgress: (Float) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        val assetUrl = release.apkAssetUrl ?: return@withContext null
        val dir = File(context.cacheDir, "updates").apply {
            // Clear stale downloads so a half-finished earlier attempt can
            // never be picked up as if it were this release's APK.
            deleteRecursively()
            mkdirs()
        }
        val target = File(dir, release.apkAssetName ?: "update.apk")
        var connection: HttpURLConnection? = null
        try {
            val url = URL(assetUrl)
            if (!url.protocol.equals("https", ignoreCase = true)) {
                Log.w(TAG, "Refusing non-HTTPS APK URL")
                return@withContext null
            }
            var conn = url.openConnection() as HttpURLConnection
            connection = conn
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            // GitHub serves release assets via a redirect to its CDN. Follow it
            // manually so each hop can be re-checked for HTTPS rather than
            // letting the stack silently downgrade to plain HTTP.
            var redirects = 0
            while (conn.responseCode in 300..399 && redirects < 5) {
                val location = conn.getHeaderField("Location") ?: break
                // Resolved against the current URL: Location may be relative.
                val next = URL(conn.url, location)
                if (!next.protocol.equals("https", ignoreCase = true)) {
                    Log.w(TAG, "Refusing non-HTTPS redirect during APK download")
                    conn.disconnect()
                    return@withContext null
                }
                conn.disconnect()
                conn = (next.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                }
                connection = conn
                redirects++
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "APK download failed: HTTP ${conn.responseCode}")
                return@withContext null
            }

            val total = if (release.apkAssetSizeBytes > 0) release.apkAssetSizeBytes
                        else conn.contentLength.toLong()
            var written = 0L
            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (total > 0) onProgress((written.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            // A connection that drops mid-body can end the stream cleanly
            // (read() returns -1) instead of throwing, which would otherwise
            // hand a truncated file to verifyApk and surface as the
            // misleading "not a valid APK" rather than a network failure.
            if (total > 0 && written != total) {
                Log.w(TAG, "APK download truncated: $written of $total bytes")
                target.delete()
                return@withContext null
            }
            onProgress(1f)
            target
        } catch (e: Exception) {
            Log.w(TAG, "APK download failed: ${e.message}")
            target.delete()
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Verifies that [apk] is a genuine update of THIS app: same package name,
     * same signing certificate. Returns null when it is, or a human-readable
     * reason when it is not.
     *
     * A non-null return means the file must be deleted and never installed.
     */
    fun verifyApk(context: Context, apk: File): String? {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES

        val downloaded = try {
            pm.getPackageArchiveInfo(apk.absolutePath, flags)
        } catch (e: Exception) {
            Log.w(TAG, "Could not read downloaded APK: ${e.message}")
            null
        } ?: return "The downloaded file is not a valid APK."

        if (downloaded.packageName != context.packageName) {
            return "The downloaded APK is for ${downloaded.packageName}, not ${context.packageName}."
        }

        val installed = try {
            pm.getPackageInfo(context.packageName, flags)
        } catch (e: Exception) {
            Log.w(TAG, "Could not read installed package: ${e.message}")
            null
        } ?: return "Could not read the installed app's signature to compare against."

        val downloadedCerts = signatureDigests(
            downloaded.signatures() ?: legacyArchiveSignatures(pm, apk)
        )
        val installedCerts = signatureDigests(
            installed.signatures() ?: legacyInstalledSignatures(pm, context.packageName)
        )

        if (downloadedCerts.isEmpty() || installedCerts.isEmpty()) {
            return "Could not extract a signing certificate to verify."
        }
        // Set comparison rather than list: signing-certificate rotation can
        // legitimately reorder or extend the set, but an update must still
        // share at least the full installed set.
        if (!downloadedCerts.containsAll(installedCerts)) {
            return "Signature mismatch — this APK was not signed with the same key as your installed app. Install cancelled."
        }
        return null
    }

    private fun android.content.pm.PackageInfo.signatures(): Array<Signature>? =
        signingInfo?.let {
            if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory
        }

    /**
     * Fallbacks for when [android.content.pm.PackageInfo.signingInfo] comes
     * back null. That happens on some OEM builds of API 28-32 for archive
     * (not-yet-installed) APKs, and would otherwise reject a perfectly valid
     * update with "Could not extract a signing certificate". GET_SIGNATURES is
     * deprecated but still populated on every API level we support; it
     * reports the current signer only, which is still a byte-exact check.
     */
    @Suppress("DEPRECATION")
    private fun legacyArchiveSignatures(pm: PackageManager, apk: File): Array<Signature>? =
        runCatching {
            pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)?.signatures
        }.getOrNull()

    @Suppress("DEPRECATION")
    private fun legacyInstalledSignatures(pm: PackageManager, packageName: String): Array<Signature>? =
        runCatching {
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
        }.getOrNull()

    /** SHA-256 of each signing certificate, for byte-exact comparison. */
    private fun signatureDigests(signatures: Array<Signature>?): Set<String> {
        val certs = signatures ?: return emptySet()
        val digest = MessageDigest.getInstance("SHA-256")
        return certs.mapNotNull { sig ->
            runCatching {
                digest.reset()
                digest.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
            }.getOrNull()
        }.toSet()
    }

    /**
     * Streams [apk] into a PackageInstaller session and commits it.
     *
     * MUST only be called after [verifyApk] has returned null for this exact
     * file. The commit hands control to the system installer, which shows its
     * own confirmation UI; the app is restarted by the system once the install
     * completes.
     */
    suspend fun install(context: Context, apk: File): String? = withContext(Dispatchers.IO) {
        try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            ).apply {
                setAppPackageName(context.packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                }
            }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("jadoo_update", 0, apk.length()).use { output ->
                    apk.inputStream().use { input -> input.copyTo(output) }
                    session.fsync(output)
                }
                // This must be an explicit component intent. The receiver has
                // no intent-filter (intentionally), so the old package-scoped
                // implicit action never resolved and the installer callback —
                // including STATUS_PENDING_USER_ACTION — was silently lost.
                val intent = Intent(context, InstallResultReceiver::class.java)
                    .setAction(INSTALL_ACTION)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
                session.commit(pending.intentSender)
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "PackageInstaller session failed", e)
            "Install failed: ${e.message}"
        }
    }
}
