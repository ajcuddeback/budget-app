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
                if (call.method != SET_SENSITIVE && call.method != CLEAR) {
                    result.notImplemented()
                    return@setMethodCallHandler
                }
                if (handleClipboard(call.method, call.argument<String>("text"))) {
                    result.success(null)
                } else {
                    result.error("unavailable", null, null)
                }
            }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * A credential the person chose to copy (an invitation link), and taking it back. On Android
     * 13+ the clip is marked ClipDescription.EXTRA_IS_SENSITIVE: the system's copy preview hides
     * it and keyboards leave it out of clipboard suggestions. Below 13 there is no such flag and
     * it is a plain copy. The clip has no label, so nothing about it is described either. Dart
     * reads the clipboard back and asks for "clear" only while it still holds that text
     * (lib/core/platform/sensitive_clipboard.dart). Needs checking on a device.
     */
    private fun handleClipboard(method: String, text: String?): Boolean {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return false
        when (method) {
            SET_SENSITIVE -> {
                val clip = ClipData.newPlainText("", text ?: return false)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    clip.description.extras =
                        PersistableBundle().apply {
                            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                        }
                }
                clipboard.setPrimaryClip(clip)
            }
            CLEAR ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    clipboard.clearPrimaryClip()
                } else {
                    clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            else -> return false
        }
        return true
    }

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
        const val CLEAR = "clear"
    }
}
