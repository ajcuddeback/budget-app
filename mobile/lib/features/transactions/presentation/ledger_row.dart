import 'package:budget_owl/core/l10n/l10n.dart';
import 'package:budget_owl/core/theme/app_theme.dart';
import 'package:budget_owl/core/theme/tokens.dart';
import 'package:budget_owl/features/transactions/domain/transaction.dart';
import 'package:material_ui/material_ui.dart';

/// What a row says where a payee's name goes.
String rowTitle(BuildContext context, Transaction t) {
  final l = context.l10n;
  if (t.transfer) return t.outgoing ? l.transactions_transferOut : l.transactions_transferIn;
  return t.payeeName ?? l.transactions_noPayee;
}

/// What a row says where a category goes: the category, "Between your accounts" for a transfer
/// (which has none and never will), or "Needs a category" — a state to act on.
String rowCategory(BuildContext context, Transaction t) {
  final l = context.l10n;
  if (t.transfer) return l.transactions_betweenAccounts;
  final name = t.categoryName;
  if (name == null) return l.transactions_needsCategory;
  final parent = t.categoryParentName;
  return parent == null ? name : l.transactions_categoryPath(parent, name);
}

/// One ledger row, as the mobile artboard draws it: an initial on a circle, the payee and its
/// category and account, and the amount in a column that lines up down the list.
///
/// The amount keeps its sign and its own currency (ADR-0022). Incoming money is coloured **and**
/// signed; "Needs a category" is coloured **and** worded — neither relies on colour alone.
class LedgerRow extends StatelessWidget {
  const LedgerRow({
    required this.transaction,
    required this.showAccount,
    this.onTap,
    this.first = false,
    this.last = false,
    super.key,
  });

  final Transaction transaction;
  final bool showAccount;
  final VoidCallback? onTap;
  final bool first;
  final bool last;

  @override
  Widget build(BuildContext context) {
    final t = transaction;
    final palette = context.palette;
    final c = palette.tokens;
    final text = Theme.of(context).textTheme;
    final l = context.l10n;
    final incoming = !t.outgoing && !t.transfer;
    final category = rowCategory(context, t);
    final secondary = [category, if (showAccount) t.accountName].join(' · ');
    final amount = context.money(t.amount);
    final badges = [
      if (t.status != TransactionStatus.cleared) context.message(t.status.messageKey),
      if (t.transfer) l.transactions_transfer,
    ];

    const big = Radius.circular(RadiusTokens.md + 2);
    const small = Radius.circular(6);
    final shape = BorderRadius.vertical(top: first ? big : small, bottom: last ? big : small);

    final avatar = Container(
      width: 36,
      height: 36,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        shape: BoxShape.circle,
        color: incoming
            ? palette.avatarIncoming
            : t.needsCategory || t.transfer
            ? c.surface
            : palette.avatar,
      ),
      child: t.transfer
          ? Icon(Icons.swap_horiz, size: 18, color: c.text)
          : Text(
              incoming
                  ? '↓'
                  : t.needsCategory
                  ? '?'
                  : (t.payeeName?.characters.first.toUpperCase() ?? '·'),
              style: typeface(
                size: 14,
                heading: true,
                color: incoming ? palette.onAvatarIncoming : palette.onAvatar,
              ),
            ),
    );

    return Padding(
      padding: const EdgeInsetsDirectional.symmetric(horizontal: SpaceTokens.s4, vertical: 1),
      child: Material(
        color: c.card,
        borderRadius: shape,
        child: InkWell(
          borderRadius: shape,
          onTap: onTap,
          child: Semantics(
            button: onTap != null,
            label:
                '${rowTitle(context, t)}, $amount, $secondary, ${context.date(t.date)}'
                '${badges.isEmpty ? '' : ', ${badges.join(', ')}'}',
            excludeSemantics: true,
            child: ConstrainedBox(
              constraints: const BoxConstraints(minHeight: 48),
              child: Padding(
                padding: const EdgeInsetsDirectional.symmetric(
                  horizontal: SpaceTokens.s4,
                  vertical: SpaceTokens.s3,
                ),
                child: _layout(
                  // Large text: the amount moves under the payee rather than squeezing the
                  // payee's name into a column one word wide (rows grow, never clip).
                  stacked: MediaQuery.textScalerOf(context).scale(1) > 1.5,
                  avatar: avatar,
                  details: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(rowTitle(context, t), style: text.titleMedium),
                      Text(
                        secondary,
                        style: text.bodySmall?.copyWith(
                          color: t.needsCategory ? palette.needsCategory : c.muted,
                          fontWeight: t.needsCategory ? FontWeight.w600 : null,
                        ),
                      ),
                      if (t.note != null)
                        Text(t.note!, style: text.bodySmall?.copyWith(color: c.muted)),
                    ],
                  ),
                  figure: Column(
                    crossAxisAlignment: CrossAxisAlignment.end,
                    children: [
                      Text(
                        amount,
                        textDirection: context.amountDirection,
                        style: text.titleMedium?.copyWith(
                          color: incoming ? palette.incoming : c.text,
                          fontFeatures: const [FontFeature.tabularFigures()],
                        ),
                      ),
                      for (final badge in badges)
                        Text(badge, style: text.labelSmall?.copyWith(color: c.accentText)),
                    ],
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  static Widget _layout({
    required bool stacked,
    required Widget avatar,
    required Widget details,
    required Widget figure,
  }) {
    if (!stacked) {
      return Row(
        children: [
          avatar,
          const SizedBox(width: SpaceTokens.s3),
          Expanded(child: details),
          const SizedBox(width: SpaceTokens.s2),
          figure,
        ],
      );
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            avatar,
            const SizedBox(width: SpaceTokens.s3),
            Expanded(child: details),
          ],
        ),
        Align(alignment: AlignmentDirectional.centerEnd, child: figure),
      ],
    );
  }
}
