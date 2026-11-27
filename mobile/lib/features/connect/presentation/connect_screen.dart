import 'package:budget_owl/core/config/environment.dart';
import 'package:budget_owl/core/l10n/l10n.dart';
import 'package:budget_owl/core/routing/router.dart';
import 'package:budget_owl/core/server/server_address.dart';
import 'package:budget_owl/core/server/server_check.dart';
import 'package:budget_owl/core/session/server_store.dart';
import 'package:budget_owl/core/session/session.dart';
import 'package:budget_owl/core/theme/tokens.dart';
import 'package:budget_owl/features/connect/data/setup_status.dart';
import 'package:budget_owl/l10n/gen/app_localizations.dart';
import 'package:budget_owl/shared/widgets/screen.dart';
import 'package:budget_owl/shared/widgets/status.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';

/// The first screen: which server is this household's? (mobile-app.md, Connecting to a server.)
///
/// Two steps on one screen. With no server chosen it asks for the address — **no default and no
/// suggestion** — and checks it with `GET /api/meta` before anything else is sent. With a verified
/// server it offers what can be done there: sign in, accept an invitation, or (only on a server
/// nobody has set up) set it up.
class ConnectScreen extends ConsumerWidget {
  const ConnectScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final session = ref.watch(sessionProvider);
    return switch (session) {
      SessionSignedOut(:final server) => _Choices(server: server),
      SessionUnsupported(:final server, :final failure) => _Unsupported(
        server: server,
        failure: failure,
      ),
      _ => const _AddressStep(),
    };
  }
}

class _AddressStep extends ConsumerStatefulWidget {
  const _AddressStep();

  @override
  ConsumerState<_AddressStep> createState() => _AddressStepState();
}

class _AddressStepState extends ConsumerState<_AddressStep> {
  final _address = TextEditingController();
  AddressProblem? _problem;
  ConnectFailure? _failure;
  bool _busy = false;

  @override
  void dispose() {
    _address.dispose();
    super.dispose();
  }

  Future<void> _connect() async {
    final parsed = ServerAddress.parse(
      _address.text,
      allowLocalCleartext: ref.read(allowLocalCleartextProvider),
    );
    switch (parsed) {
      case AddressRefused(:final problem):
        setState(() {
          _problem = problem;
          _failure = null;
        });
        return;
      case AddressAccepted(:final address):
        setState(() {
          _problem = null;
          _failure = null;
          _busy = true;
        });
        try {
          await ref.read(sessionProvider.notifier).connect(address);
        } on ConnectFailure catch (failure) {
          if (mounted) setState(() => _failure = failure);
        } finally {
          if (mounted) setState(() => _busy = false);
        }
    }
  }

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final problem = _problem;
    final failure = _failure;
    return Screen(
      title: l.connect_title,
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Lede(l.connect_lede),
          Gutter(
            child: TextField(
              controller: _address,
              keyboardType: TextInputType.url,
              autocorrect: false,
              enableSuggestions: false,
              textInputAction: TextInputAction.go,
              onSubmitted: (_) => _busy ? null : _connect(),
              decoration: InputDecoration(
                labelText: l.connect_address,
                helperText: l.connect_addressHint,
                errorText: problem == null ? null : addressProblemText(l, problem),
              ),
            ),
          ),
          if (problem == AddressProblem.cleartext)
            Gutter(child: Text(l.connect_problem_cleartextHelp)),
          if (failure != null) Gutter(child: NoticeBanner(text: connectFailureText(l, failure))),
          Gutter(
            child: FilledButton(
              onPressed: _busy ? null : _connect,
              child: Text(_busy ? l.connect_checking : l.connect_continue),
            ),
          ),
        ],
      ),
    );
  }
}

String addressProblemText(AppLocalizations l, AddressProblem problem) => switch (problem) {
  AddressProblem.empty => l.connect_problem_empty,
  AddressProblem.invalid => l.connect_problem_invalid,
  AddressProblem.cleartext => l.connect_problem_cleartext,
  AddressProblem.path => l.connect_problem_path,
  AddressProblem.query => l.connect_problem_query,
  AddressProblem.credentials => l.connect_problem_credentials,
};

String connectFailureText(AppLocalizations l, ConnectFailure failure) => switch (failure.problem) {
  ConnectProblem.hostNotFound => l.connect_error_hostNotFound,
  ConnectProblem.unreachable => l.connect_error_unreachable,
  ConnectProblem.certificateUntrusted => l.connect_error_certificateUntrusted,
  ConnectProblem.certificateWrongName => l.connect_error_certificateWrongName,
  ConnectProblem.notBudgetOwl => l.connect_error_notBudgetOwl,
  ConnectProblem.busy => l.connect_error_busy,
  ConnectProblem.versionMismatch => l.connect_error_versionMismatch(
    '${failure.serverApiVersion ?? '?'}',
    '$clientApiVersion',
  ),
  ConnectProblem.transport => l.connect_error_transport,
  ConnectProblem.phoneNotCleared => l.connect_error_phoneNotCleared,
};

class _Choices extends ConsumerWidget {
  const _Choices({required this.server});

  final ServerAddress server;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l = context.l10n;
    final status = ref.watch(setupStatusProvider);
    final fresh = status.value?.setupComplete == false;
    return Screen(
      title: l.connect_connectedTo(server.display),
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Lede(fresh ? l.connect_setUpHint : l.connect_connectedLede),
          // Which CA on this phone vouches for this server, when one does (S1).
          if (ref.watch(pinnedCaProvider) != null)
            Gutter(child: TrustedThroughNote(changeServer: l.connect_changeServer)),
          if (fresh)
            Gutter(
              child: FilledButton(
                onPressed: () => context.go(Routes.setup),
                child: Text(l.connect_setUp),
              ),
            )
          else
            Gutter(
              child: FilledButton(
                onPressed: () => context.go(Routes.signIn),
                child: Text(l.connect_signIn),
              ),
            ),
          Gutter(
            child: OutlinedButton(
              onPressed: () => context.go(Routes.join),
              child: Text(l.connect_join),
            ),
          ),
          if (status.hasError) Gutter(child: NoticeBanner(text: l.error_network)),
          const SizedBox(height: SpaceTokens.s4),
          Gutter(
            child: TextButton(
              onPressed: () => ref.read(sessionProvider.notifier).signOut(forgetServer: true),
              child: Text(l.connect_changeServer),
            ),
          ),
        ],
      ),
    );
  }
}

class _Unsupported extends ConsumerWidget {
  const _Unsupported({required this.server, required this.failure});

  final ServerAddress server;
  final ConnectFailure failure;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l = context.l10n;
    return Screen(
      title: server.display,
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Gutter(child: NoticeBanner(text: connectFailureText(l, failure))),
          Gutter(
            child: FilledButton(
              onPressed: () => ref.invalidate(sessionProvider),
              child: Text(l.common_retry),
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
    );
  }
}
