import Flutter
import UIKit

@main
@objc class AppDelegate: FlutterAppDelegate, FlutterImplicitEngineDelegate {
  override func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
  ) -> Bool {
    return super.application(application, didFinishLaunchingWithOptions: launchOptions)
  }

  func didInitializeImplicitFlutterEngine(_ engineBridge: FlutterImplicitEngineBridge) {
    GeneratedPluginRegistrant.register(with: engineBridge.pluginRegistry)
    guard let registrar = engineBridge.pluginRegistry.registrar(forPlugin: "BudgetOwlPlatform")
    else { return }
    let messenger = registrar.messenger()
    FlutterMethodChannel(name: AppDelegate.clipboardChannel, binaryMessenger: messenger)
      .setMethodCallHandler { [weak self] call, result in
        guard let self = self else {
          result(FlutterError(code: "unavailable", message: nil, details: nil))
          return
        }
        self.handleClipboard(call, result: result)
      }
    FlutterMethodChannel(name: AppDelegate.filesChannel, binaryMessenger: messenger)
      .setMethodCallHandler { call, result in
        guard call.method == AppDelegate.pickerInbox else {
          result(FlutterMethodNotImplemented)
          return
        }
        result(AppDelegate.pickerInboxPaths())
      }
  }

  // MARK: The clipboard (lib/core/platform/sensitive_clipboard.dart)

  static let clipboardChannel = "app.budgetowl/clipboard"
  static let setSensitive = "setSensitive"
  static let clearIfUnchanged = "clearIfUnchanged"

  /// How long a copied credential lives on the pasteboard (SensitiveClipboard.iosExpiry).
  static let expiry: TimeInterval = 10 * 60

  /// The one copy this app may take back: the pasteboard's changeCount right after it, never its
  /// text.
  private var copied: (receipt: Int, changeCount: Int)?
  private var receipts = 0

  /// A credential the person chose to copy (an invitation link): localOnly, so Universal
  /// Clipboard never carries it to their other devices, and expiring by itself after ten minutes.
  /// Taking it back compares UIPasteboard.changeCount, which reads nothing: no "Allow Paste?"
  /// prompt (iOS 16+), and it works while the app is in the background. Needs checking on a
  /// device.
  private func handleClipboard(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    let pasteboard = UIPasteboard.general
    switch call.method {
    case AppDelegate.setSensitive:
      guard let arguments = call.arguments as? [String: Any],
        let text = arguments["text"] as? String
      else {
        result(FlutterError(code: "bad_arguments", message: nil, details: nil))
        return
      }
      pasteboard.setItems(
        [["public.utf8-plain-text": text]],
        options: [
          .localOnly: true,
          .expirationDate: Date().addingTimeInterval(AppDelegate.expiry),
        ])
      receipts += 1
      copied = (receipt: receipts, changeCount: pasteboard.changeCount)
      result(receipts)
    case AppDelegate.clearIfUnchanged:
      guard let arguments = call.arguments as? [String: Any],
        let receipt = arguments["receipt"] as? Int,
        let record = copied, record.receipt == receipt
      else {
        result("changed")
        return
      }
      copied = nil
      guard pasteboard.changeCount == record.changeCount else {
        result("changed")
        return
      }
      pasteboard.items = []
      result("cleared")
    default:
      result(FlutterMethodNotImplemented)
    }
  }

  // MARK: The picker's copies (lib/core/storage/temp_files.dart)

  static let filesChannel = "app.budgetowl/files"
  static let pickerInbox = "pickerInbox"

  /// file_selector_ios opens documents "as a copy", into tmp/<bundle id>-Inbox/. Dart's temporary
  /// directory is Library/Caches, so the sweep is told both: tmp/ itself, and that folder in it.
  static func pickerInboxPaths() -> [String: String]? {
    guard let bundle = Bundle.main.bundleIdentifier else { return nil }
    let temporary = URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
    return [
      "temporary": temporary.path,
      "inbox": temporary.appendingPathComponent("\(bundle)-Inbox", isDirectory: true).path,
    ]
  }
}
