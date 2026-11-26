import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/http/reachability.dart';
import 'package:budget_owl/core/l10n/l10n.dart';
import 'package:budget_owl/shared/widgets/status.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

/// A failed or refused write, in one sentence, **said as of the moment it failed**.
///
/// A write that never reached the server — refused before sending because writes are off, or
/// lost on the way — says why writes are off, through [writesOffText]: *offline*, or *the server's
/// version is not confirmed yet*. Both arrive as a connectivity [AppError] (the writers and the
/// transport refuse with `AppError.offline()`), so the reason is read from [reachabilityProvider],
/// not from the error. Anything the server answered is its code's sentence.
///
/// Called where the failure is caught, and the text kept, so a banner does not change its story
/// when the phone's state does.
String writeFailureText(BuildContext context, WidgetRef ref, AppError error) =>
    error.isConnectivity
    ? writesOffText(context.l10n, ref.read(reachabilityProvider))
    : context.errorMessage(error);

/// Why a write control is off right now, for a tap on one that is shown but disabled.
String writesOffNow(BuildContext context, WidgetRef ref) =>
    writesOffText(context.l10n, ref.read(reachabilityProvider));
