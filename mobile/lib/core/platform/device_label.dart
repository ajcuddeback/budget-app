import 'package:budget_owl/l10n/gen/app_localizations.dart';
import 'package:flutter/foundation.dart';

/// The label a new token is issued under, shown on the member's devices list (ADR-0018):
/// "Phone · Android", "iPhone · iOS". The person may edit it before signing in.
///
/// From the platform only. A model name ("Pixel 8") needs a device-information plugin, which is
/// another native dependency for a label the person can type themselves.
String deviceLabelFor(TargetPlatform platform, AppLocalizations l) => switch (platform) {
  TargetPlatform.iOS => l.deviceLabel_ios,
  _ => l.deviceLabel_android,
};

/// The most the server stores (AuthController: device labels are trimmed to 100).
const int maximumDeviceLabelLength = 100;
