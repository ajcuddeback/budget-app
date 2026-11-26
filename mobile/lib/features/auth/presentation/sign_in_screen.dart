import 'package:budget_owl/core/config/environment.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/l10n/l10n.dart';
import 'package:budget_owl/core/platform/device_label.dart';
import 'package:budget_owl/core/session/session.dart';
import 'package:budget_owl/features/connect/data/setup_status.dart';
import 'package:budget_owl/shared/widgets/screen.dart';
import 'package:budget_owl/shared/widgets/status.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

/// Email, password, and a name for this phone (authentication-and-households.md, Sign in).
///
/// The password is sent only to the server that answered `/api/meta` over verified TLS, and only
/// in this one request; it is not kept, logged or put anywhere a crash could carry it. One
/// sentence for every bad credential — the server's `authentication-failed` — and the wait for a
/// `429`.
class SignInScreen extends ConsumerStatefulWidget {
  const SignInScreen({super.key});

  @override
  ConsumerState<SignInScreen> createState() => _SignInScreenState();
}

class _SignInScreenState extends ConsumerState<SignInScreen> {
  final _email = TextEditingController();
  final _password = TextEditingController();
  final _label = TextEditingController();
  AppError? _error;
  bool _busy = false;
  bool _prefilled = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (!_prefilled) {
      _prefilled = true;
      _label.text = deviceLabelFor(ref.read(targetPlatformProvider), context.l10n);
      final session = ref.read(sessionProvider);
      if (session is SessionSignedOut && session.email != null) _email.text = session.email!;
    }
  }

  @override
  void dispose() {
    _email.dispose();
    _password.dispose();
    _label.dispose();
    super.dispose();
  }

  Future<void> _signIn() async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final label = _label.text.trim();
      await ref.read(sessionProvider.notifier).signIn(
        email: _email.text.trim(),
        password: _password.text,
        deviceLabel: label.isEmpty
            ? deviceLabelFor(ref.read(targetPlatformProvider), context.l10n)
            : label,
      );
    } on AppError catch (error) {
      if (mounted) setState(() => _error = error);
    } finally {
      // The password leaves memory with the field: nothing else held it.
      _password.clear();
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final session = ref.watch(sessionProvider);
    final server = session is SessionSignedOut ? session.server : null;
    final notice = session is SessionSignedOut ? session.notice : null;
    final passwordLogin = ref.watch(setupStatusProvider).value?.passwordLoginEnabled ?? true;
    final error = _error;

    return Screen(
      title: l.login_title,
      body: AutofillGroup(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (server != null) Lede(l.login_server(server.display)),
            if (notice != null)
              Gutter(
                child: NoticeBanner(
                  success: notice != SignInNotice.expired,
                  text: switch (notice) {
                    SignInNotice.expired => l.login_notice_expired,
                    SignInNotice.created => l.login_notice_created,
                    SignInNotice.joined => l.login_notice_joined,
                  },
                ),
              ),
            if (!passwordLogin) Gutter(child: NoticeBanner(text: l.login_passwordLoginDisabled)),
            Gutter(
              child: TextField(
                controller: _email,
                keyboardType: TextInputType.emailAddress,
                autocorrect: false,
                enableSuggestions: false,
                autofillHints: const [AutofillHints.email, AutofillHints.username],
                textInputAction: TextInputAction.next,
                decoration: InputDecoration(labelText: l.login_email),
              ),
            ),
            Gutter(
              child: TextField(
                controller: _password,
                obscureText: true,
                autocorrect: false,
                enableSuggestions: false,
                autofillHints: const [AutofillHints.password],
                textInputAction: TextInputAction.next,
                decoration: InputDecoration(labelText: l.login_password),
              ),
            ),
            Gutter(
              child: TextField(
                controller: _label,
                maxLength: maximumDeviceLabelLength,
                maxLengthEnforcement: MaxLengthEnforcement.enforced,
                textInputAction: TextInputAction.done,
                decoration: InputDecoration(
                  labelText: l.login_deviceLabel,
                  helperText: l.login_deviceLabelHint,
                ),
              ),
            ),
            if (error != null) Gutter(child: NoticeBanner(text: context.errorMessage(error))),
            Gutter(
              child: FilledButton(
                onPressed: _busy || !passwordLogin ? null : _signIn,
                child: Text(_busy ? l.login_signingIn : l.login_signIn),
              ),
            ),
            Gutter(
              child: TextButton(
                onPressed: () => ref.read(sessionProvider.notifier).signOut(forgetServer: true),
                child: Text(l.connect_changeServer),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
