import 'package:budget_owl/core/config/environment.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/l10n/l10n.dart';
import 'package:budget_owl/core/platform/device_label.dart';
import 'package:budget_owl/core/session/session.dart';
import 'package:budget_owl/shared/widgets/screen.dart';
import 'package:budget_owl/shared/widgets/status.dart';
import 'package:budget_owl/shared/widgets/validation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

/// First-run setup on a server nobody is on yet (`POST /api/setup/first-user`), offered only
/// while `GET /api/setup/status` says so. The account is created, then signed in with the same
/// credentials — the server hands out none from setup (authentication-and-households.md).
///
/// The operator is told, here, that they will be able to see everything anyone in the household
/// records; anyone they invite is told the same before joining.
class SetupScreen extends ConsumerStatefulWidget {
  const SetupScreen({super.key});

  @override
  ConsumerState<SetupScreen> createState() => _SetupScreenState();
}

class _SetupScreenState extends ConsumerState<SetupScreen> {
  final _form = GlobalKey<FormState>();
  final _name = TextEditingController();
  final _email = TextEditingController();
  final _password = TextEditingController();
  final _household = TextEditingController();
  final _currency = TextEditingController();
  AppError? _error;
  bool _busy = false;

  @override
  void dispose() {
    for (final c in [_name, _email, _password, _household, _currency]) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> _create() async {
    if (!(_form.currentState?.validate() ?? false)) return;
    final api = ref.read(apiClientProvider);
    if (api == null) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    final email = _email.text.trim();
    final password = _password.text;
    try {
      final csrf = await csrfForPublicWrite(api);
      await api.write(
        'POST',
        '/api/setup/first-user',
        public: true,
        headers: csrf.headers,
        body: {
          'displayName': _name.text.trim(),
          'email': email,
          'password': password,
          'householdName': _household.text.trim(),
          'baseCurrency': _currency.text.trim().toUpperCase(),
        },
      );
      if (!mounted) return;
      await ref.read(sessionProvider.notifier).signIn(
        email: email,
        password: password,
        deviceLabel: deviceLabelFor(ref.read(targetPlatformProvider), context.l10n),
      );
    } on AppError catch (error) {
      if (mounted) setState(() => _error = error);
    } finally {
      _password.clear();
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final error = _error;
    return Screen(
      title: l.setup_title,
      body: Form(
        key: _form,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Lede(l.setup_lede),
            Gutter(child: NoticeBanner(text: l.setup_operatorNote)),
            Gutter(
              child: TextFormField(
                controller: _name,
                textInputAction: TextInputAction.next,
                decoration: InputDecoration(labelText: l.setup_name),
                validator: (v) => Rules.required(l, v ?? ''),
              ),
            ),
            Gutter(
              child: TextFormField(
                controller: _email,
                keyboardType: TextInputType.emailAddress,
                autocorrect: false,
                enableSuggestions: false,
                textInputAction: TextInputAction.next,
                decoration: InputDecoration(labelText: l.setup_email),
                validator: (v) => Rules.email(l, v ?? ''),
              ),
            ),
            Gutter(
              child: TextFormField(
                controller: _password,
                obscureText: true,
                autocorrect: false,
                enableSuggestions: false,
                textInputAction: TextInputAction.next,
                decoration: InputDecoration(
                  labelText: l.setup_password,
                  helperText: l.setup_passwordHint('${Rules.minimumPasswordLength}'),
                ),
                validator: (v) => Rules.password(l, v ?? ''),
              ),
            ),
            Gutter(
              child: TextFormField(
                controller: _household,
                textInputAction: TextInputAction.next,
                decoration: InputDecoration(labelText: l.setup_householdName),
                validator: (v) => Rules.required(l, v ?? ''),
              ),
            ),
            Gutter(
              child: TextFormField(
                controller: _currency,
                autocorrect: false,
                textCapitalization: TextCapitalization.characters,
                maxLength: 3,
                decoration: InputDecoration(
                  labelText: l.setup_baseCurrency,
                  helperText: l.setup_baseCurrencyHint,
                ),
                validator: (v) => Rules.currency(l, v ?? ''),
              ),
            ),
            if (error != null) Gutter(child: NoticeBanner(text: context.errorMessage(error))),
            Gutter(
              child: FilledButton(
                onPressed: _busy ? null : _create,
                child: Text(_busy ? l.setup_creating : l.setup_create),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
