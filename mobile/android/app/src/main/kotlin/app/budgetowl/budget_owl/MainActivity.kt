package app.budgetowl.budget_owl

import android.os.Build
import android.os.Bundle
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
        // reads only the system store (ADR-0042). The app's network_security_config already trusts
        // exactly these ("<certificates src="user" />"); this hands Dart the same set, read-only,
        // as DER bytes. Nothing is written, and no verification is relaxed: Dart still checks the
        // chain and the host name. lib/core/http/user_trust.dart is the other end.
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
    }

    private val mainHandler = Handler(Looper.getMainLooper())

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
    }
}
