import 'package:budget_owl/core/dates/calendar_date.dart';
import 'package:budget_owl/core/l10n/l10n.dart';
import 'package:budget_owl/core/theme/tokens.dart';
import 'package:budget_owl/features/categories/data/categories.dart';
import 'package:budget_owl/features/check_in/domain/check_in.dart';
import 'package:budget_owl/features/check_in/presentation/check_in_text.dart';
import 'package:budget_owl/shared/widgets/screen.dart';
import 'package:material_ui/material_ui.dart';

/// What the category sheet chose: a category, or [clear] to take the answer back.
final class CategoryPick {
  const CategoryPick(this.categoryId) : clear = false;

  const CategoryPick.clear() : categoryId = null, clear = true;

  final String? categoryId;
  final bool clear;
}

/// The category picker as a sheet, as the step 2 artboard draws it ("Choose" opens it).
///
/// Archived categories are not offered (weekly-check-in.md, step 2); each child is named with its
/// parent. With an answer given, "Choose a category" takes it back, as the web's empty option does.
Future<CategoryPick?> showCategorySheet(
  BuildContext context, {
  required Item item,
  required List<Category> categories,
}) => showModalBottomSheet<CategoryPick>(
  context: context,
  useRootNavigator: true,
  isScrollControlled: true,
  builder: (sheetContext) {
    final l = sheetContext.l10n;
    final chosen = item.chosenCategoryId;
    final options = pickableCategories(categories);
    return SafeArea(
      child: ConstrainedBox(
        constraints: BoxConstraints(maxHeight: MediaQuery.sizeOf(sheetContext).height * 0.8),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Gutter(
              child: Semantics(
                header: true,
                child: Text(
                  l.checkIn_categorize_category,
                  style: Theme.of(sheetContext).textTheme.titleLarge,
                ),
              ),
            ),
            if (item.payeeName != null) Lede(item.payeeName!),
            Flexible(
              child: ListView(
                shrinkWrap: true,
                children: [
                  if (item.answer != null)
                    ListTile(
                      leading: const Icon(Icons.undo),
                      title: Text(l.checkIn_categorize_choose),
                      onTap: () => Navigator.of(sheetContext).pop(const CategoryPick.clear()),
                    ),
                  for (final category in options)
                    ListTile(
                      selected: category.id == chosen,
                      trailing: category.id == chosen ? const Icon(Icons.check) : null,
                      title: Text(
                        category.parentName == null
                            ? category.name
                            : l.transactions_categoryTrail(category.parentName!, category.name),
                      ),
                      onTap: () => Navigator.of(sheetContext).pop(CategoryPick(category.id)),
                    ),
                ],
              ),
            ),
            const SizedBox(height: SpaceTokens.s2),
          ],
        ),
      ),
    );
  },
);

/// What "Different bill" chose before it is sent.
final class BillPick {
  const BillPick({required this.billId, required this.dueDate, required this.remember});

  final String billId;
  final CalendarDate dueDate;
  final bool remember;
}

/// "Different bill": which bill, which of its due dates, and whether to remember the mapping.
/// Remembering starts ticked for a repeating charge, as the web's picker does.
Future<BillPick?> showBillSheet(
  BuildContext context, {
  required Item item,
  required List<BillChoice> choices,
}) => showModalBottomSheet<BillPick>(
  context: context,
  useRootNavigator: true,
  isScrollControlled: true,
  builder: (_) => _BillSheet(item: item, choices: choices),
);

class _BillSheet extends StatefulWidget {
  const _BillSheet({required this.item, required this.choices});

  final Item item;
  final List<BillChoice> choices;

  @override
  State<_BillSheet> createState() => _BillSheetState();
}

class _BillSheetState extends State<_BillSheet> {
  late BillChoice? _bill = widget.choices.isEmpty ? null : widget.choices.first;
  late CalendarDate? _date = _bill?.dueDates.isEmpty ?? true ? null : _bill!.dueDates.first;
  late bool _remember = widget.item.reason == ChargeReason.recurring;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final words = CheckInWords(context);
    final bill = _bill;
    final date = _date;
    return SafeArea(
      child: Padding(
        padding: EdgeInsetsDirectional.only(
          start: SpaceTokens.s4,
          end: SpaceTokens.s4,
          bottom: MediaQuery.viewInsetsOf(context).bottom + SpaceTokens.s4,
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const SizedBox(height: SpaceTokens.s3),
            Semantics(
              header: true,
              child: Text(
                l.checkIn_charge_differentBill,
                style: Theme.of(context).textTheme.titleLarge,
              ),
            ),
            const SizedBox(height: SpaceTokens.s3),
            DropdownButtonFormField<String>(
              initialValue: bill?.billId,
              isExpanded: true,
              decoration: InputDecoration(labelText: l.checkIn_charge_whichBill),
              items: [
                for (final choice in widget.choices)
                  DropdownMenuItem(
                    value: choice.billId,
                    child: Text('${choice.name} · ${words.money(choice.expected)}'),
                  ),
              ],
              onChanged: (id) => setState(() {
                _bill = widget.choices.firstWhere((c) => c.billId == id);
                _date = _bill!.dueDates.isEmpty ? null : _bill!.dueDates.first;
              }),
            ),
            const SizedBox(height: SpaceTokens.s3),
            DropdownButtonFormField<String>(
              key: ValueKey(bill?.billId),
              initialValue: date?.iso,
              isExpanded: true,
              decoration: InputDecoration(labelText: l.checkIn_charge_whichDate),
              items: [
                for (final due in bill?.dueDates ?? const <CalendarDate>[])
                  DropdownMenuItem(value: due.iso, child: Text(words.date(due))),
              ],
              onChanged: (iso) =>
                  setState(() => _date = iso == null ? null : CalendarDate.parse(iso)),
            ),
            CheckboxListTile(
              contentPadding: EdgeInsetsDirectional.zero,
              controlAffinity: ListTileControlAffinity.leading,
              value: _remember,
              onChanged: (value) => setState(() => _remember = value ?? false),
              title: Text(l.checkIn_charge_remember),
            ),
            FilledButton(
              onPressed: bill == null || date == null
                  ? null
                  : () =>
                        Navigator.of(context)
                            .pop(BillPick(billId: bill.billId, dueDate: date, remember: _remember)),
              child: Text(l.checkIn_charge_attachChosen),
            ),
            TextButton(onPressed: () => Navigator.of(context).pop(), child: Text(l.common_cancel)),
          ],
        ),
      ),
    );
  }
}
