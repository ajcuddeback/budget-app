import 'package:budget_owl/core/routing/router.dart';
import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';

/// Back to the connect screen's choices — sign in, join, set up — from any one of them.
class BackToConnect extends StatelessWidget {
  const BackToConnect({super.key});

  @override
  Widget build(BuildContext context) => BackButton(onPressed: () => context.go(Routes.connect));
}
