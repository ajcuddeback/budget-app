package app.budgetowl.budget_owl

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.os.Handler
import android.os.Looper
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.security.KeyStore
import java.security.MessageDigest

class MainActivity : FlutterActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 13+: the system takes no screenshot of this app for the recents screen, so a
        // balance never sits in the task switcher (docs/features/mobile-app.md, attack 7). The
        // Flutter PrivacyCover stays for older versions and for iOS. Unlike FLAG_SECURE this
        // leaves the user's own screenshots working. Needs checking on a device: no emulator or
        // test here can show what the recents screen draws.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(false)
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        // The certificate authorities the person installed on this phone (Settings, Security,
        // Encryption & credentials, Install a certificate, CA certificate), for Dart's TLS, which
        // reads only the system store (ADR-0042). The app's network_security_config trusts these
        // ("<certificates src="user" />"); this hands them to Dart, read-only, as DER bytes, and
        // Dart trusts at most ONE of them per server: the one it was verified through when it was
        // connected to (pinned by fingerprint), and none for a server the system roots verify.
        // Nothing is written, and no verification is relaxed: Dart still checks the chain and the
        // host name. Read again each time the app returns to the foreground.
        // lib/core/http/user_trust.dart and http_adapter.dart are the other end.
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, TRUST_CHANNEL)
            .setMethodCallHandler { call, result ->
                if (call.method != USER_CA_CERTIFICATES) {
                    result.notImplemented()
                    return@setMethodCallHandler
                }
                // Off the main thread: loading the store reads files. The answer goes back on it.
                Thread {
                    val certificates =
                        try {
                            userCaCertificates()
                        } catch (failure: Exception) {
                            null
                        }
                    mainHandler.post {
                        // A failure says nothing about why: Dart falls back to the system roots.
                        if (certificates == null) {
                            result.error("unavailable", null, null)
                        } else {
                            result.success(certificates)
                        }
                    }
                }.start()
            }
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CLIPBOARD_CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    SET_SENSITIVE -> {
                        val text = call.argument<String>("text")
                        val receipt = text?.let { copySensitive(it) }
                        if (receipt == null) {
                            result.error("unavailable", null, null)
                        } else {
                            result.success(receipt)
                        }
                    }
                    CLEAR_IF_UNCHANGED -> {
                        val copy =
                            Receipt(
                                call.argument<Number>(TIMESTAMP)?.toLong(),
                                call.argument<String>(DIGEST),
                            )
                        result.success(clearIfUnchanged(copy))
                    }
                    else -> result.notImplemented()
                }
            }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * What recognises one copy: its ClipDescription timestamp (Android 8+), or a digest of its
     * text below 8. It is Dart's to keep and hand back (round 6, V2): nothing about a copy lives
     * on this activity, so one recreated between the copy and the take-back can still compare.
     */
    private class Receipt(val timestamp: Long?, val digest: String?)

    /** A take-back asked for while the app was not focused, checked again on focus. */
    private var pendingClear: Receipt? = null

    /**
     * A credential the person chose to copy (an invitation link). On Android 13+ the clip is
     * marked ClipDescription.EXTRA_IS_SENSITIVE: the system's copy preview hides it and keyboards
     * leave it out of clipboard suggestions. Below 13 nothing can mark it: it is a plain clip
     * until it is taken back. The clip has no label. Returns the receipt for [clearIfUnchanged].
     * Needs checking on a device.
     */
    private fun copySensitive(text: String): Map<String, Any?>? {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return null
        val clip = ClipData.newPlainText("", text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras =
                PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
        }
        clipboard.setPrimaryClip(clip)
        pendingClear = null
        // Our own clip, read back at once while focused: its timestamp, set by the system, is how
        // it is recognised later (Android 8+). Below 8 a digest of the text is kept instead.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mapOf(TIMESTAMP to clipboard.primaryClipDescription?.timestamp)
        } else {
            mapOf(DIGEST to sha256Hex(text))
        }
    }

    /**
     * Empties the clipboard if it still holds the copy [copy] describes, **without reading what
     * is on it** on Android 8+: only the clip's description, whose timestamp is compared, which
     * shows no "pasted from your clipboard" notice (that is getPrimaryClip, Android 12+). Below 8
     * a digest of the clip is compared; those versions have no notice and no background limit.
     * Android 10+ refuses any clipboard access to an app that is not focused: then the check is
     * deferred to the next time the window has focus ([onWindowFocusChanged]).
     */
    private fun clearIfUnchanged(copy: Receipt): String {
        if (!hasWindowFocus()) {
            pendingClear = copy
            return DEFERRED
        }
        pendingClear = null
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return CHANGED
        val unchanged =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val now = clipboard.primaryClipDescription
                copy.timestamp != null && now != null && now.timestamp == copy.timestamp
            } else {
                val clip = clipboard.primaryClip
                val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text else null
                copy.digest != null && text != null && sha256Hex(text.toString()) == copy.digest
            }
        if (!unchanged) return CHANGED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            clipboard.clearPrimaryClip()
        } else {
            clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        }
        return CLEARED
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        val pending = pendingClear
        if (hasFocus && pending != null) clearIfUnchanged(pending)
    }

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** The DER encoding of every enabled user-installed CA: AndroidCAStore aliases "user:…". */
    private fun userCaCertificates(): List<ByteArray> {
        val store = KeyStore.getInstance("AndroidCAStore")
        store.load(null)
        return store.aliases().toList()
            .filter { it.startsWith(USER_ALIAS_PREFIX) }
            .mapNotNull { store.getCertificate(it)?.encoded }
    }

    private companion object {
        const val TRUST_CHANNEL = "app.budgetowl/trust"
        const val USER_CA_CERTIFICATES = "userCaCertificates"
        const val USER_ALIAS_PREFIX = "user:"
        const val CLIPBOARD_CHANNEL = "app.budgetowl/clipboard"
        const val SET_SENSITIVE = "setSensitive"
        const val CLEAR_IF_UNCHANGED = "clearIfUnchanged"
        const val CLEARED = "cleared"
        const val CHANGED = "changed"
        const val DEFERRED = "deferred"
        const val TIMESTAMP = "timestamp"
        const val DIGEST = "digest"
    }
}
