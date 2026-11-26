package app.budgetowl.budget_owl

import android.os.Build
import android.os.Bundle
import io.flutter.embedding.android.FlutterActivity

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
}
