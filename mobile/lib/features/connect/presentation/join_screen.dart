import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/json/json.dart';
import 'package:budget_owl/core/l10n/l10n.dart';
import 'package:budget_owl/core/routing/router.dart';
import 'package:budget_owl/core/server/server_address.dart';
import 'package:budget_owl/core/session/session.dart';
import 'package:budget_owl/core/theme/app_theme.dart';
import 'package:budget_owl/core/theme/tokens.dart';
import 'package:budget_owl/features/connect/domain/invitation_link.dart';
import 'package:budget_owl/features/connect/presentation/back_to_connect.dart';
import 'package:budget_owl/shared/widgets/screen.dart';
import 'package:budget_owl/shared/widgets/status.dart';
import 'package:budget_owl/shared/widgets/validation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';

/// Accepting an invitation with a pasted link (`POST /api/invitations/{token}/accept`).
///
/// **The disclosure is a security control, not copy** (ADR-0026): before an account exists, the
/// screen says in full-size body text, above the button, that the person running the server can
/// see everything they record. The button's semantics carry it too, so a screen-reader user hears
/// it at the button. Weakening or burying that wording is a security change.
class JoinScreen extends ConsumerStatefulWidget {
  const JoinScreen({super.key});

  @override
  ConsumerState<JoinScreen> createState() => _JoinScreenState();
}

class _JoinScreenState extends ConsumerState<JoinScreen> {
  final _link = TextEditingController();
  final _form = GlobalKey<FormState>();
  final _name = TextEditingController();
  final _password = TextEditingController();
  InvitationLink? _parsed;
  String? _token;
  AppError? _error;
  bool _busy = false;
  JsonMap? _joined;

  @override
  void dispose() {
    _link.dispose();
    _name.dispose();
    _password.dispose();
    super.dispose();
  }

  void _read(ServerAddress server) {
    final parsed = parseInvitationLink(_link.text, server);
    setState(() {
      _parsed = parsed;
      _token = parsed is InvitationForThisServer ? parsed.token : null;
      // The link field held a credential; once read, it is not kept on screen.
      if (_token != null) _link.clear();
    });
  }

  Future<void> _accept() async {
    final token = _token;
    final api = ref.read(apiClientProvider);
    if (token == null || api == null) return;
    if (!(_form.currentState?.validate() ?? false)) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final csrf = await csrfForPublicWrite(api);
      final joined = await api.write(
        'POST',
        '/api/invitations/${Uri.encodeComponent(token)}/accept',
        public: true,
        headers: csrf.headers,
        body: {
          if (_name.text.trim().isNotEmpty) 'displayName': _name.text.trim(),
          if (_password.text.isNotEmpty) 'password': _password.text,
        },
      );
      if (mounted) setState(() => _joined = joined);
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
    final session = ref.watch(sessionProvider);
    if (session is! SessionSignedOut) return const SizedBox.shrink();
    final joined = _joined;
    if (joined != null) return _Joined(joined: joined);
    final token = _token;
    final parsed = _parsed;

    if (token == null) {
      return Screen(
        leading: const BackToConnect(),
        title: l.join_title,
        body: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Lede(l.join_pasteHint),
            Gutter(
              child: TextField(
                controller: _link,
                keyboardType: TextInputType.url,
                autocorrect: false,
                enableSuggestions: false,
                decoration: InputDecoration(
                  labelText: l.join_pasteLabel,
                  errorText: switch (parsed) {
                    NotAnInvitation() => l.join_problem_notALink,
                    InvitationForAnotherServer(:final host) => l.join_problem_otherHost(host),
                    _ => null,
                  },
                ),
              ),
            ),
            Gutter(
              child: FilledButton(
                onPressed: () => _read(session.server),
                child: Text(l.connect_continue),
              ),
            ),
            Gutter(
              child: TextButton(
                onPressed: () => context.go(Routes.connect),
                child: Text(l.common_cancel),
              ),
            ),
          ],
        ),
      );
    }

    final error = _error;
    final disclosure = [
      l.join_disclosure_operator,
      l.join_disclosure_nothingHidden,
      l.join_disclosure_trust,
    ];
    return Screen(
      leading: const BackToConnect(),
      title: l.join_title,
      body: Form(
        key: _form,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Lede(l.join_lede),
            Gutter(
              child: _Disclosure(heading: l.join_disclosure_heading, lines: disclosure),
            ),
            Gutter(child: Text(l.join_accountNote)),
            Gutter(
              child: TextFormField(
                controller: _name,
                decoration: InputDecoration(labelText: l.join_name),
                textInputAction: TextInputAction.next,
              ),
            ),
            Gutter(
              child: TextFormField(
                controller: _password,
                obscureText: true,
                autocorrect: false,
                enableSuggestions: false,
                decoration: InputDecoration(labelText: l.join_password),
                validator: (v) => (v ?? '').isEmpty ? null : Rules.password(l, v ?? ''),
              ),
            ),
            if (error != null) Gutter(child: NoticeBanner(text: context.errorMessage(error))),
            Gutter(
              child: Semantics(
                hint: disclosure.join(' '),
                child: FilledButton(
                  onPressed: _busy ? null : _accept,
                  child: Text(_busy ? l.join_joining : l.join_accept),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// The join-time disclosure: heavy, full-size, in the flow, never collapsed.
class _Disclosure extends StatelessWidget {
  const _Disclosure({required this.heading, required this.lines});

  final String heading;
  final List<String> lines;

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    final text = Theme.of(context).textTheme;
    return Container(
      padding: const EdgeInsetsDirectional.all(SpaceTokens.s4),
      decoration: BoxDecoration(
        color: Color.alphaBlend(c.accent.withAlpha(36), c.bg),
        borderRadius: BorderRadius.circular(RadiusTokens.md),
        border: Border.all(color: c.accentText, width: 2),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Semantics(header: true, child: Text(heading, style: text.titleLarge)),
          const SizedBox(height: SpaceTokens.s2),
          for (final line in lines)
            Padding(
              padding: const EdgeInsetsDirectional.only(top: SpaceTokens.s1),
              child: Text(line, style: text.bodyLarge),
            ),
        ],
      ),
    );
  }
}

class _Joined extends ConsumerWidget {
  const _Joined({required this.joined});

  final JsonMap joined;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l = context.l10n;
    final household = joined['householdName'] is String ? joined['householdName']! as String : '';
    final role = joined['role'] is String ? joined['role']! as String : '';
    final email = joined['email'] is String ? joined['email']! as String : null;
    final created = joined['userCreated'] == true;
    return Screen(
      title: l.join_joinedTitle,
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Gutter(
            child: Text(
              l.join_joinedHeading(household),
              style: Theme.of(context).textTheme.titleLarge,
            ),
          ),
          Gutter(child: Text(l.join_joinedRole(context.message('role.$role')))),
          if (created && email != null) Gutter(child: Text(l.join_joinedAccountCreated(email))),
          Gutter(
            child: FilledButton(
              onPressed: () {
                ref.read(sessionProvider.notifier).signedOutWith(SignInNotice.joined, email: email);
                context.go(Routes.signIn);
              },
              child: Text(l.join_signInNow),
            ),
          ),
        ],
      ),
    );
  }
}
