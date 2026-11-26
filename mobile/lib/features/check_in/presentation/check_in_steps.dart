import 'package:budget_owl/core/l10n/l10n.dart';
import 'package:budget_owl/core/money/money.dart';
import 'package:budget_owl/core/theme/app_theme.dart';
import 'package:budget_owl/core/theme/tokens.dart';
import 'package:budget_owl/features/check_in/domain/check_in.dart';
import 'package:budget_owl/features/check_in/domain/requests.dart';
import 'package:budget_owl/features/check_in/presentation/check_in_text.dart';
import 'package:material_ui/material_ui.dart';

/// What a step may do. Every write goes back to the screen, which holds the one write in flight
/// and decides what a refusal means.
abstract interface class CheckInActions {
  /// A draft, and a role that may answer it. A `VIEWER` gets no control at all.
  bool get editable;

  /// [editable], online, and nothing else being saved.
  bool get enabled;

  String? staleFor(Item item);

  bool prompted(Item item);

  void answer(Item item, Body body);

  void togglePrompt(Item item, {required bool value});

  void pickCategory(Item item);

  void toggleRemember(Item item, {required bool value});

  void pickBill(Item item);

  void allocate(Choice choice);

  void finish();

  void discard();

  void open(String route);
}

/// One shown step, as one screen (weekly-check-in.md, "Mobile: one step per screen").
class StepSection extends StatelessWidget {
  const StepSection({
    required this.step,
    required this.number,
    required this.soFar,
    required this.billChoices,
    required this.actions,
    super.key,
  });

  final CheckInStep step;

  /// Its position among the shown steps, from 1.
  final int number;
  final bool soFar;
  final List<BillChoice> billChoices;
  final CheckInActions actions;

  @override
  Widget build(BuildContext context) {
    final words = CheckInWords(context);
    final text = Theme.of(context).textTheme;
    return Padding(
      padding: const EdgeInsetsDirectional.symmetric(horizontal: SpaceTokens.s4),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Wrap(
            spacing: SpaceTokens.s2,
            runSpacing: SpaceTokens.s1,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              Semantics(
                header: true,
                child: Text(
                  context.l10n.checkIn_stepNumbered(words.count(number), words.stepName(step.step)),
                  style: text.labelLarge?.copyWith(color: context.colors.muted),
                ),
              ),
              Tag(words.stepStatus(step)),
            ],
          ),
          const SizedBox(height: SpaceTokens.s2),
          Semantics(header: true, child: Text(words.heading(step), style: text.titleLarge)),
          const SizedBox(height: SpaceTokens.s3),
          ...switch (step.step) {
            StepName.whatsNew => _whatsNew(context, words),
            StepName.categorize => _categorize(context, words),
            StepName.bills => _bills(context, words),
            StepName.nextWeek => _nextWeek(context, words),
          },
        ],
      ),
    );
  }

  // ------------------------------------------------------------------------- 1 · what's new

  List<Widget> _whatsNew(BuildContext context, CheckInWords words) {
    final l = context.l10n;
    return [
      for (final line in step.income ?? const <IncomeLine>[])
        InfoCard(
          title: line.landed ? l.checkIn_income_heading : l.checkIn_income_headingNotYet,
          lines: words.income(line),
        ),
      for (final item in step.items)
        ItemCard(
          item: item,
          kindLabel: item.kind == ItemKind.unusualCharge
              ? l.checkIn_odd_heading
              : l.checkIn_overdue_heading,
          title: item.payeeName ?? item.billName,
          amount: item.amount,
          words: words,
          actions: actions,
          children: [
            if (words.reason(item) case final reason?) Muted(reason),
            if (item.kind == ItemKind.unusualCharge) Question(words.prepaid(item)),
            if (actions.editable) ...[
              if (item.debtPromptName != null && item.kind == ItemKind.overdueBill)
                _prompt(context, item),
              ChoiceRow([
                if (item.kind == ItemKind.unusualCharge) ...[
                  if (item.multiple != null)
                    _choice(item, l.checkIn_odd_yesPrepaid, AnswerChoice.prepaid, () {
                      actions.answer(item, choiceOnly(AnswerChoice.prepaid));
                    }),
                ] else ...[
                  _choice(item, l.checkIn_overdue_markPaid, AnswerChoice.markPaid, () {
                    actions.answer(item, markPaid(recordDebtPayment: actions.prompted(item)));
                  }),
                  _choice(item, l.checkIn_overdue_skip, AnswerChoice.skip, () {
                    actions.answer(item, choiceOnly(AnswerChoice.skip));
                  }),
                ],
                _choice(item, l.checkIn_leaveIt, AnswerChoice.leave, () {
                  actions.answer(item, choiceOnly(AnswerChoice.leave));
                }),
              ]),
            ] else if (item.answer != null)
              Muted(l.checkIn_chosen(words.choiceName(item.answer!.choice))),
          ],
        ),
      if (step.items.isNotEmpty) Muted(l.checkIn_whatsNew_quiet),
    ];
  }

  // ------------------------------------------------------------------------- 2 · categorize

  List<Widget> _categorize(BuildContext context, CheckInWords words) {
    final l = context.l10n;
    final placed = step.items.where((i) => i.answer != null || i.preAnswer != null).length;
    final spending = step.weekSpending;
    return [
      Muted(l.checkIn_categorize_placed(words.count(placed), words.count(step.items.length))),
      const SizedBox(height: SpaceTokens.s2),
      for (final item in step.items)
        ItemCard(
          item: item,
          title: item.payeeName ?? '—',
          amount: item.amount,
          signed: true,
          words: words,
          actions: actions,
          tag: item.byRule ? l.checkIn_yourRule : null,
          children: [
            if (actions.editable) ...[
              ChoiceRow([
                _CategoryButton(item: item, actions: actions),
                _choice(item, l.checkIn_leaveIt, AnswerChoice.leave, () {
                  actions.answer(item, choiceOnly(AnswerChoice.leave));
                }),
              ]),
              if (item.payeeName != null)
                CheckboxListTile(
                  contentPadding: EdgeInsetsDirectional.zero,
                  controlAffinity: ListTileControlAffinity.leading,
                  value: item.answer?.remember ?? false,
                  onChanged: actions.enabled && item.chosenCategoryId != null
                      ? (value) => actions.toggleRemember(item, value: value ?? false)
                      : null,
                  title: Text(l.checkIn_categorize_always),
                ),
              if (words.replacesRule(item) case final replaces?) Muted(replaces),
            ] else
              Muted(
                item.chosenCategoryName ??
                    (item.answer?.choice == AnswerChoice.leave
                        ? l.checkIn_choiceName_LEAVE
                        : l.checkIn_categorize_choose),
              ),
          ],
        ),
      if ((step.more ?? 0) > 0)
        Wrap(
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            Muted(l.checkIn_categorize_more(words.count(step.more!))),
            TextButton(
              onPressed: () => actions.open('/transactions'),
              child: Text(l.checkIn_categorize_moreLink),
            ),
          ],
        ),
      if (spending != null)
        InfoCard(
          title: l.checkIn_weekInOneLine,
          lines: [words.spent(spending, soFar: soFar)],
        ),
    ];
  }

  // ------------------------------------------------------------------------------ 3 · bills

  List<Widget> _bills(BuildContext context, CheckInWords words) {
    final l = context.l10n;
    final text = Theme.of(context).textTheme;
    return [
      for (final item in step.items)
        ItemCard(
          item: item,
          kindLabel: item.question ? l.checkIn_bills_mapHeading : null,
          title: item.payeeName ?? '—',
          amount: item.amount,
          words: words,
          actions: actions,
          children: [
            if (words.reason(item) case final reason?) Question(reason),
            if (words.attachedTo(item) case final attached?) Muted(attached),
            if (actions.editable) ...[
              if (item.debtPromptName != null) _prompt(context, item),
              ChoiceRow([
                if (item.reason != ChargeReason.ruleMatch &&
                    (item.suggestedBillId != null ||
                        item.reason == ChargeReason.ruleOutsideTolerance))
                  _choice(
                    item,
                    item.reason == ChargeReason.recurring
                        ? l.checkIn_charge_mapIt
                        : l.checkIn_charge_attach,
                    AnswerChoice.attach,
                    () {
                      final body = attachSuggested(item, recordDebtPayment: actions.prompted(item));
                      if (body == null) {
                        actions.pickBill(item);
                      } else {
                        actions.answer(item, body);
                      }
                    },
                  ),
                if (billChoices.isNotEmpty)
                  OutlinedButton(
                    onPressed: actions.enabled ? () => actions.pickBill(item) : null,
                    child: Text(l.checkIn_charge_differentBill),
                  ),
                _choice(item, l.checkIn_charge_notABill, AnswerChoice.notABill, () {
                  actions.answer(item, choiceOnly(AnswerChoice.notABill));
                }),
                if (!item.question)
                  _choice(item, l.checkIn_leaveIt, AnswerChoice.leave, () {
                    actions.answer(item, choiceOnly(AnswerChoice.leave));
                  }),
              ]),
            ] else if (item.answer != null && item.answer!.choice != AnswerChoice.attach)
              Muted(l.checkIn_chosen(words.choiceName(item.answer!.choice))),
          ],
        ),
      if ((step.bills ?? const []).isNotEmpty)
        Card(
          child: Padding(
            padding: const EdgeInsetsDirectional.symmetric(vertical: SpaceTokens.s2),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                for (final line in step.bills!)
                  Padding(
                    padding: const EdgeInsetsDirectional.symmetric(
                      horizontal: SpaceTokens.s4,
                      vertical: SpaceTokens.s2,
                    ),
                    child: Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(line.billName, style: text.titleMedium),
                              Text(
                                words.occurrence(line),
                                style: text.bodySmall?.copyWith(
                                  color: line.overdue
                                      ? context.palette.needsCategory
                                      : context.colors.muted,
                                ),
                              ),
                            ],
                          ),
                        ),
                        const SizedBox(width: SpaceTokens.s2),
                        Amount(line.amount),
                      ],
                    ),
                  ),
              ],
            ),
          ),
        ),
      if (step.stillToPay case final still?)
        InfoCard(title: l.checkIn_bills_stillToPay, amount: still),
    ];
  }

  // -------------------------------------------------------------------------- 4 · next week

  List<Widget> _nextWeek(BuildContext context, CheckInWords words) {
    final l = context.l10n;
    final text = Theme.of(context).textTheme;
    final figures = step.figures;
    final allocation = step.allocation;
    final offers =
        figures != null && figures.hasSurplus && allocation != null && figures.surplusWire != null;
    final rolled = allocation?.answer?.choice == AnswerChoice.rollOver;
    final figure = figures == null
        ? null
        : rolled && figures.rolledOver != null
        ? figures.rolledOver
        : figures.base;
    final perDay = figures == null
        ? null
        : rolled && figures.rolledPerDay != null
        ? figures.rolledPerDay
        : figures.basePerDay;
    return [
      if (words.targetLine(figures) case final target?) Muted(target),
      if (offers) ...[
        const SizedBox(height: SpaceTokens.s2),
        Semantics(header: true, child: Text(l.checkIn_next_where, style: text.titleMedium)),
        if (!figures.surplusAllocatable) Muted(l.checkIn_next_short),
        if (actions.staleFor(allocation) case final stale?) StaleMark(stale),
        // Nothing is preselected: the canvas's tick on a debt would make the step's only decision
        // for the person (weekly-check-in.md, "Where the surplus goes").
        RadioGroup<Choice>(
          groupValue: _allocated(allocation, step.choices ?? const []),
          onChanged: (choice) {
            if (choice != null && actions.enabled) actions.allocate(choice);
          },
          child: Column(
            children: [
              for (final choice in step.choices ?? const <Choice>[])
                Card(
                  child: RadioListTile<Choice>(
                    value: choice,
                    enabled: actions.editable && actions.enabled,
                    title: Text(words.choiceLabel(choice), style: text.titleMedium),
                    subtitle: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        for (final effect in words.choiceEffect(choice, figures)) Text(effect),
                      ],
                    ),
                  ),
                ),
            ],
          ),
        ),
      ],
      if (figure != null && perDay != null)
        // Replaced from the server's answer after each save, never worked out here; announced
        // when it changes.
        Semantics(
          liveRegion: true,
          container: true,
          child: Card(
            child: Padding(
              padding: const EdgeInsetsDirectional.all(SpaceTokens.s4),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    l.checkIn_next_youCanSpend.toUpperCase(),
                    style: text.labelSmall?.copyWith(color: context.colors.muted),
                  ),
                  const SizedBox(height: SpaceTokens.s1),
                  Text(
                    words.money(figure),
                    textDirection: context.amountDirection,
                    style: text.displaySmall?.copyWith(
                      color: figure.isNegative ? context.palette.needsCategory : null,
                    ),
                  ),
                  Text(
                    l.checkIn_next_perDay(words.money(perDay)),
                    style: text.bodyMedium?.copyWith(color: context.colors.muted),
                  ),
                  if (figure.isNegative) Muted(l.checkIn_next_negative),
                ],
              ),
            ),
          ),
        ),
      if (words.dueNextWeek(step.dueNextWeek ?? const []) case final due?) Muted(due),
      if (words.streak(step.streak) case final streak?) Muted(streak),
      if (actions.editable) ...[
        const SizedBox(height: SpaceTokens.s3),
        FilledButton(
          onPressed: actions.enabled ? actions.finish : null,
          child: Text(l.checkIn_finishMobile),
        ),
        TextButton(
          onPressed: actions.enabled ? actions.discard : null,
          child: Text(l.checkIn_discard),
        ),
      ],
    ];
  }

  Choice? _allocated(Item? allocation, List<Choice> choices) {
    final answer = allocation?.answer;
    if (answer == null) return null;
    for (final choice in choices) {
      if (choice.choice != answer.choice) continue;
      if (choice.choice == AnswerChoice.debt && choice.debtId != answer.debtId) continue;
      if (choice.choice == AnswerChoice.goal && choice.goalId != answer.goalId) continue;
      return choice;
    }
    return null;
  }

  // ---------------------------------------------------------------------------------- parts

  Widget _prompt(BuildContext context, Item item) => CheckboxListTile(
    contentPadding: EdgeInsetsDirectional.zero,
    controlAffinity: ListTileControlAffinity.leading,
    value: actions.prompted(item),
    onChanged: actions.enabled
        ? (value) => actions.togglePrompt(item, value: value ?? false)
        : null,
    title: Text(context.l10n.checkIn_recordAgainst(item.debtPromptName!)),
  );

  Widget _choice(Item item, String label, AnswerChoice choice, VoidCallback onPressed) =>
      ChoiceButton(
        label: label,
        chosen: item.chosen == choice,
        onPressed: actions.enabled ? onPressed : null,
      );
}

/// "Choose a category", or the category chosen with a tick; opens the picker sheet.
class _CategoryButton extends StatelessWidget {
  const _CategoryButton({required this.item, required this.actions});

  final Item item;
  final CheckInActions actions;

  @override
  Widget build(BuildContext context) {
    final name = item.chosenCategoryName;
    return ChoiceButton(
      label: name ?? context.l10n.checkIn_categorize_choose,
      chosen: name != null,
      onPressed: actions.enabled ? () => actions.pickCategory(item) : null,
    );
  }
}

/// One question's card: what kind it is, the payee or bill, date and account, the amount, then
/// whatever the step adds.
class ItemCard extends StatelessWidget {
  const ItemCard({
    required this.item,
    required this.title,
    required this.words,
    required this.actions,
    required this.children,
    this.kindLabel,
    this.amount,
    this.signed = false,
    this.tag,
    super.key,
  });

  final Item item;
  final String? kindLabel;

  /// A payee from a bank file: its own text, never inside a sentence.
  final String? title;
  final Money? amount;

  /// Step 2 shows the signed amount (a deposit is a deposit); steps 1 and 3 a charge's size.
  final bool signed;
  final String? tag;
  final CheckInWords words;
  final CheckInActions actions;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    final text = Theme.of(context).textTheme;
    final stale = actions.staleFor(item);
    final by = words.answeredBy(item);
    final shown = amount == null
        ? null
        : signed
        ? amount!
        : Money(amount!.amount.abs(), amount!.currency);
    return Card(
      shape: stale == null
          ? null
          : RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(RadiusTokens.md),
              side: BorderSide(color: context.colors.accentText, width: 2),
            ),
      child: Padding(
        padding: const EdgeInsetsDirectional.all(SpaceTokens.s4),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (kindLabel != null)
              Text(
                kindLabel!.toUpperCase(),
                style: text.labelSmall?.copyWith(color: context.colors.muted),
              ),
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      if (title != null) Text(title!, style: text.titleMedium),
                      Text(
                        words.itemLine(item),
                        style: text.bodySmall?.copyWith(color: context.colors.muted),
                      ),
                    ],
                  ),
                ),
                if (shown != null) ...[const SizedBox(width: SpaceTokens.s2), Amount(shown)],
              ],
            ),
            if (tag != null) ...[const SizedBox(height: SpaceTokens.s1), Tag(tag!)],
            if (stale != null) StaleMark(stale),
            ...children,
            if (by != null) Muted(by),
          ],
        ),
      ),
    );
  }
}

/// A choice button that says when it is the one chosen, in words to a screen reader and with a
/// tick on screen — never by colour alone.
class ChoiceButton extends StatelessWidget {
  const ChoiceButton({required this.label, required this.chosen, this.onPressed, super.key});

  final String label;
  final bool chosen;
  final VoidCallback? onPressed;

  @override
  Widget build(BuildContext context) => Semantics(
    selected: chosen,
    child: chosen
        ? FilledButton.icon(
            onPressed: onPressed,
            icon: const Icon(Icons.check, size: 18),
            label: Text(label),
          )
        : OutlinedButton(onPressed: onPressed, child: Text(label)),
  );
}

class ChoiceRow extends StatelessWidget {
  const ChoiceRow(this.children, {super.key});

  final List<Widget> children;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsetsDirectional.only(top: SpaceTokens.s2),
    child: Wrap(spacing: SpaceTokens.s2, runSpacing: SpaceTokens.s2, children: children),
  );
}

class InfoCard extends StatelessWidget {
  const InfoCard({required this.title, this.lines = const [], this.amount, super.key});

  final String title;
  final List<String> lines;
  final Money? amount;

  @override
  Widget build(BuildContext context) {
    final text = Theme.of(context).textTheme;
    return Card(
      color: context.colors.surface,
      child: Padding(
        padding: const EdgeInsetsDirectional.all(SpaceTokens.s4),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(title, style: text.titleMedium),
                  for (final line in lines) Text(line, style: text.bodyMedium),
                ],
              ),
            ),
            if (amount != null) Amount(amount!),
          ],
        ),
      ),
    );
  }
}

/// An amount as the server sent it, in its own currency, reading in the language's direction.
class Amount extends StatelessWidget {
  const Amount(this.value, {super.key});

  final Money value;

  @override
  Widget build(BuildContext context) => Text(
    context.money(value),
    textDirection: context.amountDirection,
    style: Theme.of(context).textTheme.titleMedium
        ?.copyWith(fontFeatures: const [FontFeature.tabularFigures()]),
  );
}

class Tag extends StatelessWidget {
  const Tag(this.label, {super.key});

  final String label;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsetsDirectional.symmetric(
      horizontal: SpaceTokens.s2,
      vertical: SpaceTokens.s1,
    ),
    decoration: BoxDecoration(
      color: context.colors.surface,
      borderRadius: BorderRadius.circular(RadiusTokens.sm),
      border: Border.all(color: context.colors.divider),
    ),
    child: Text(label, style: Theme.of(context).textTheme.labelMedium),
  );
}

class Muted extends StatelessWidget {
  const Muted(this.text, {super.key});

  final String text;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsetsDirectional.only(top: SpaceTokens.s1),
    child: Text(
      text,
      style: Theme.of(context).textTheme.bodyMedium?.copyWith(color: context.colors.muted),
    ),
  );
}

class Question extends StatelessWidget {
  const Question(this.text, {super.key});

  final String text;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsetsDirectional.only(top: SpaceTokens.s2),
    child: Text(text, style: Theme.of(context).textTheme.bodyLarge),
  );
}

/// Why a stale refusal named this item, in a sentence.
class StaleMark extends StatelessWidget {
  const StaleMark(this.text, {super.key});

  final String text;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsetsDirectional.only(top: SpaceTokens.s2),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Icon(Icons.error_outline, size: 18, color: context.colors.accentText),
        const SizedBox(width: SpaceTokens.s1),
        Expanded(
          child: Text(
            text,
            style: Theme.of(context).textTheme.bodyMedium
                ?.copyWith(color: context.colors.accentText),
          ),
        ),
      ],
    ),
  );
}
