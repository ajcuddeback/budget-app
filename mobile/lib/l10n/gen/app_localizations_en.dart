// ignore: unused_import
import 'package:intl/intl.dart' as intl;

import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for English (`en`).
class AppLocalizationsEn extends AppLocalizations {
  AppLocalizationsEn([String locale = 'en']) : super(locale);

  @override
  String get accounts_activeTag => 'In use';

  @override
  String get accounts_add => 'Add an account';

  @override
  String get accounts_addFirst => 'Add your first account';

  @override
  String get accounts_addTitle => 'Add an account';

  @override
  String get accounts_added => 'Account added.';

  @override
  String get accounts_archive => 'Archive';

  @override
  String accounts_archiveHeading(String name) {
    return 'Archive $name?';
  }

  @override
  String get accounts_archiveMessage =>
      'It keeps its history, hides it from lists, and stops taking new transactions. Its balance leaves the subtotal for its currency, so that figure will change. You can bring it back whenever you like.';

  @override
  String get accounts_archiveMessagePhone =>
      'It keeps its history, hides it from lists, and stops taking new transactions. You can bring it back whenever you like.';

  @override
  String get accounts_archivedHeading => 'Archived';

  @override
  String get accounts_archivedNote => 'These keep their history and take no new transactions.';

  @override
  String get accounts_archivedNoteWritable =>
      'These keep their history and take no new transactions. Bring one back at any time.';

  @override
  String get accounts_archivedNotice => 'That account has been archived.';

  @override
  String get accounts_archivedTag => 'Archived';

  @override
  String get accounts_conflictBody =>
      'Nothing you typed has been saved. This is what the account says now:';

  @override
  String accounts_conflictField(String field, String value) {
    return '$field: $value';
  }

  @override
  String get accounts_conflictOverwrite => 'Keep mine and save';

  @override
  String get accounts_conflictTitle => 'Somebody else saved first';

  @override
  String get accounts_conflictUseLatest => 'Use theirs';

  @override
  String get accounts_currency => 'Currency';

  @override
  String get accounts_currencyHint =>
      'The currency this account is held in. Nothing is ever converted behind your back.';

  @override
  String get accounts_currencyLocked =>
      'The currency cannot be changed once an account has transactions: the amounts already recorded would quietly start meaning something else.';

  @override
  String get accounts_delete => 'Delete';

  @override
  String accounts_deleteHeading(String name) {
    return 'Delete $name?';
  }

  @override
  String get accounts_deleteMessage =>
      'This permanently removes the account, and it cannot be undone. To keep an account\'s history, archive it instead.';

  @override
  String get accounts_deletedNotice => 'That account has been deleted.';

  @override
  String get accounts_edit => 'Edit';

  @override
  String get accounts_editTitle => 'Edit account';

  @override
  String get accounts_emptyBody =>
      'An account is a place money sits — a current account, a savings pot, a credit card, or cash in a tin. Add the ones you have and you can start recording what happens in them.';

  @override
  String get accounts_emptyReadOnly =>
      'Your role in this household is read-only, so someone else will need to add them.';

  @override
  String get accounts_heading => 'Your accounts';

  @override
  String get accounts_hideArchived => 'Hide archived';

  @override
  String get accounts_import => 'Import';

  @override
  String get accounts_lastFour => 'Last four digits';

  @override
  String get accounts_lastFourHint =>
      'Optional, and only if it has a number. It is shown beside the name so you can tell two similar accounts apart. Clear this box to remove them.';

  @override
  String get accounts_lastFourNone => 'none';

  @override
  String get accounts_lede =>
      'Where your household\'s money sits. Tell Budget Owl about each account once, and transactions can be recorded against the right one.';

  @override
  String get accounts_name => 'Name';

  @override
  String get accounts_nameHint =>
      'Whatever you call it. Two accounts may share a name — the last four digits tell them apart.';

  @override
  String accounts_nameWithDigits(String name, String digits) {
    return '$name •$digits';
  }

  @override
  String accounts_notShown(String count) {
    return '$count more accounts are not shown here.';
  }

  @override
  String get accounts_notShownOne => '1 more account is not shown here.';

  @override
  String get accounts_openingBalance => 'Opening balance';

  @override
  String get accounts_openingBalanceHint =>
      'What the account held when you started tracking it. A credit card you owe on starts negative, like -400. Use digits and a dot.';

  @override
  String get accounts_save => 'Save account';

  @override
  String get accounts_saved => 'Account saved.';

  @override
  String accounts_showArchived(String count) {
    return 'Show $count archived';
  }

  @override
  String get accounts_state => 'Status';

  @override
  String get accounts_subtotal => 'Subtotal';

  @override
  String get accounts_subtotalUnavailable => 'Subtotal unavailable';

  @override
  String get accounts_title => 'Accounts';

  @override
  String get accounts_type => 'Type';

  @override
  String get accounts_type_CASH => 'Cash';

  @override
  String get accounts_type_CHECKING => 'Current account';

  @override
  String get accounts_type_CREDIT_CARD => 'Credit card';

  @override
  String get accounts_type_INVESTMENT => 'Investment';

  @override
  String get accounts_type_SAVINGS => 'Savings';

  @override
  String get accounts_type_unknown => 'Other';

  @override
  String get accounts_unarchive => 'Bring back';

  @override
  String get accounts_unarchivedNotice => 'That account is back in your lists.';

  @override
  String get admin_accountCountLabel => 'Accounts';

  @override
  String get admin_accountsEmpty => 'Nobody can sign in.';

  @override
  String get admin_accountsHeading => 'Accounts';

  @override
  String get admin_accountsLede =>
      'Everybody who can sign in to this server. Read-only here — suspending or removing an account is not something this screen does.';

  @override
  String get admin_administratorLabel => 'Administrator';

  @override
  String get admin_administratorTag => 'Administrator';

  @override
  String get admin_conflictChanged => 'What changed:';

  @override
  String get admin_conflictMessage =>
      'These settings changed after this page read them, so nothing has been saved and what you entered is still here.';

  @override
  String get admin_conflictNothingVisible =>
      'Nothing visible here changed, but somebody saved these settings while you were editing them.';

  @override
  String get admin_conflictTitle => 'Somebody else saved first';

  @override
  String get admin_field_oidcClientId => 'Client ID';

  @override
  String get admin_field_oidcClientSecret => 'Client secret';

  @override
  String get admin_field_oidcEnabled => 'Single sign-on';

  @override
  String get admin_field_oidcIssuerUri => 'Issuer URL';

  @override
  String get admin_field_oidcProvisioningEnabled => 'Account creation through the provider';

  @override
  String get admin_field_passwordLoginEnabled => 'Email and password sign-in';

  @override
  String get admin_field_registrationOpen => 'Registration';

  @override
  String get admin_instanceHeading => 'This instance';

  @override
  String admin_joined(String date) {
    return 'Joined $date';
  }

  @override
  String get admin_keepMine => 'Save my changes anyway';

  @override
  String get admin_lede =>
      'Who can sign in to this server, and how. Everything else an operator does — backups, upgrades, the database — is done on the machine, because the machine is yours.';

  @override
  String get admin_nothingChanged => 'Nothing had changed, so nothing was saved.';

  @override
  String get admin_oidcClientId => 'Client ID';

  @override
  String get admin_oidcClientIdHint => 'The identifier your provider issued for Budget Owl.';

  @override
  String get admin_oidcIssuer => 'Issuer URL';

  @override
  String get admin_oidcIssuerHint =>
      'Where your provider publishes its configuration, such as https://id.example.org. Use https: the client secret is sent to this host.';

  @override
  String get admin_oidcLede =>
      'Optional. If you already run an identity provider, this adds a second way in. It changes nothing about what anybody may see once they are signed in.';

  @override
  String get admin_oidcLegend => 'Single sign-on';

  @override
  String get admin_oidcNotProven =>
      'No owner has signed in through your provider yet. Until one has, email and password sign-in stays on.';

  @override
  String get admin_oidcOffConfirm => 'Turn it off';

  @override
  String get admin_oidcOffHeading => 'Turn off single sign-on?';

  @override
  String get admin_oidcOffMessage =>
      'Email and password sign-in will be switched back on, so that nobody is left without a way in. Your provider\'s details are cleared, and anybody who signed in through it will need a password instead.';

  @override
  String get admin_oidcOffWarning =>
      'Turning this off also switches email and password sign-in back on, so that nobody is left without a way in.';

  @override
  String admin_oidcProven(String date) {
    return 'An owner signed in through your provider on $date, so email and password sign-in can now be turned off.';
  }

  @override
  String get admin_oidcProvisioning =>
      'Create an account for anybody who signs in through the provider';

  @override
  String get admin_oidcProvisioningHint =>
      'An account on its own sees nothing. Joining the household still needs an invitation, so nobody in your provider\'s directory lands inside your finances.';

  @override
  String get admin_oidcSecret => 'Client secret';

  @override
  String get admin_oidcSecretHint =>
      'Budget Owl never shows a stored secret back — not to anybody, including you. Leave this empty to keep the one you have, or type a new one to replace it. If you cannot remember it, replace it.';

  @override
  String get admin_oidcSwitch => 'Offer sign-in through my provider';

  @override
  String get admin_passwordHint =>
      'Email and password is a permanent part of Budget Owl. Once another way in is working you can stop offering this one, but it is always in the build and can always be switched back on.';

  @override
  String get admin_passwordLegend => 'Email and password';

  @override
  String get admin_passwordLockedRule =>
      'An owner has to complete a sign-in through your provider first. Proving the new way in works before the old one is hidden is what stops you being locked out of your own records, on your own hardware, with nobody to call.';

  @override
  String get admin_passwordLockedTitle => 'This cannot be turned off yet';

  @override
  String get admin_passwordOffRecovery =>
      'If your provider later stops working, this is switched back on from the machine itself. On the server, run:';

  @override
  String get admin_passwordOffRecoveryAfter =>
      'Nothing in the browser can help once the browser is the thing you cannot get in through.';

  @override
  String get admin_passwordOffRecoveryCommand => 'deploy/restore-password-login.sh';

  @override
  String get admin_passwordOn => 'Email and password sign-in is on.';

  @override
  String get admin_passwordSwitch => 'Offer sign-in with an email address and a password';

  @override
  String get admin_registrationConsequence =>
      'While registration is open, anybody who can reach this server over the network can create themselves an account. If it is reachable from the internet, that means anybody at all. Leave it closed and invite the people you want instead.';

  @override
  String get admin_registrationLegend => 'Registration';

  @override
  String get admin_registrationOpening => 'Saving now will open registration.';

  @override
  String get admin_registrationSwitch => 'Let anyone create an account';

  @override
  String get admin_saved => 'Saved.';

  @override
  String get admin_secretNotSet => 'No secret set.';

  @override
  String get admin_secretSet => 'A secret is set.';

  @override
  String get admin_setupLabel => 'Set up';

  @override
  String get admin_setupUnrecorded => 'Not recorded';

  @override
  String get admin_signingInHeading => 'Signing in';

  @override
  String get admin_status_ACTIVE => 'Active';

  @override
  String get admin_status_DISABLED => 'Disabled';

  @override
  String get admin_status_unknown => 'Unknown status';

  @override
  String get admin_title => 'Administration';

  @override
  String get admin_useLatest => 'Take the latest';

  @override
  String get admin_versionLabel => 'Version';

  @override
  String get app_name => 'Budget Owl';

  @override
  String get app_skipToContent => 'Skip to main content';

  @override
  String get bills_actualAmount => 'Amount paid';

  @override
  String get bills_actualAmountHint =>
      'What really left the account. The budgeted figure is filled in for you.';

  @override
  String get bills_actualAmountHintBlank => 'What really left the account.';

  @override
  String get bills_add => 'Add a bill';

  @override
  String get bills_addFirst => 'Add your first bill';

  @override
  String get bills_addTitle => 'Add a bill';

  @override
  String get bills_amountVaries => 'The amount varies';

  @override
  String get bills_amountVariesHint =>
      'For a bill that is never quite the same twice, like electricity.';

  @override
  String get bills_anchorDate => 'Counting from';

  @override
  String get bills_anchorDateHint =>
      'A date it was due. “Every other week” needs one, or it names two different sets of days.';

  @override
  String get bills_billsCountEitherWay =>
      'Bills count here whether or not you have paid them. Money that has already gone is not money you still have, so ticking one off does not change this figure.';

  @override
  String get bills_category => 'Category';

  @override
  String get bills_choose => 'Choose…';

  @override
  String get bills_created => 'Bill added.';

  @override
  String get bills_dayOfMonth => 'Day of the month';

  @override
  String get bills_dayOfMonthHint =>
      'The 29th, 30th and 31st fall on the last day of a shorter month.';

  @override
  String get bills_dayOfWeek => 'Day of the week';

  @override
  String bills_debtExtraExceedsLeft(String extra, String left) {
    return 'Your debt plan puts $extra a month towards debt beyond the minimums, which is more than the $left left this month after your goals. Nothing has been changed — adjust the plan or a goal if you need to.';
  }

  @override
  String bills_deleteHeading(String name) {
    return 'Delete $name?';
  }

  @override
  String get bills_deleteMessage =>
      'Everything recorded about this bill goes with it. Your transactions are kept — a bill is a plan, not a payment. This can\'t be undone.';

  @override
  String get bills_deleted => 'Bill deleted.';

  @override
  String get bills_dueDate => 'Date it was due';

  @override
  String get bills_dueDateHint =>
      'An irregular bill has no date of its own, so this is the date the payment is recorded against.';

  @override
  String get bills_editTitle => 'Edit bill';

  @override
  String get bills_emptyBody =>
      'A bill is something that leaves on a date — rent, a phone contract, a season ticket. Set one up and it is listed before it is due, so nothing surprises you.';

  @override
  String get bills_emptyReadOnly => 'Ask an owner or member of your household to set some up.';

  @override
  String get bills_envelopes_allocated => 'Set aside this month';

  @override
  String get bills_envelopes_body =>
      'The other half of the subtraction: what you have set aside for the spending that changes week to week. Envelopes have a screen of their own.';

  @override
  String get bills_envelopes_heading => 'Envelopes';

  @override
  String get bills_envelopes_open => 'Open envelopes';

  @override
  String get bills_expectedAmount => 'Amount';

  @override
  String bills_expectedAmountHint(String currency) {
    return 'What you expect it to be, in $currency.';
  }

  @override
  String get bills_expectedLabel => 'Budgeted';

  @override
  String get bills_forget => 'Forget';

  @override
  String get bills_forgetHeading => 'Forget what was recorded?';

  @override
  String get bills_forgetMessage =>
      'This removes what was recorded for that date, so it reads as planned again. It is not the same as marking it due: that is you saying it is unpaid, and this is saying nothing at all.';

  @override
  String get bills_forgotten => 'Forgotten. That date reads as planned again.';

  @override
  String get bills_goalsNote =>
      'Goals count what you plan to set aside each month, or what you recorded if that is more. Money already saved before you started tracking a goal is never counted.';

  @override
  String get bills_heading => 'Your bills';

  @override
  String get bills_howOften => 'How often';

  @override
  String get bills_income_account => 'Account it lands in';

  @override
  String get bills_income_add => 'Add an income source';

  @override
  String get bills_income_addFirst => 'Add your first income source';

  @override
  String get bills_income_addTitle => 'Add an income source';

  @override
  String get bills_income_amount => 'Amount each time';

  @override
  String bills_income_amountHint(String currency) {
    return 'What arrives on each date, in $currency. The month\'s total is worked out from the schedule.';
  }

  @override
  String bills_income_arrivals(String count) {
    return '$count arrivals this month';
  }

  @override
  String get bills_income_arrivalsOne => '1 arrival this month';

  @override
  String get bills_income_created => 'Income source added.';

  @override
  String bills_income_deleteHeading(String name) {
    return 'Delete $name?';
  }

  @override
  String get bills_income_deleteMessage =>
      'This income source goes, along with what it contributed to any month\'s figures. Your transactions are kept. This can\'t be undone.';

  @override
  String get bills_income_deleted => 'Income source deleted.';

  @override
  String get bills_income_eachLabel => 'Each time';

  @override
  String get bills_income_editTitle => 'Edit income source';

  @override
  String get bills_income_emptyBody =>
      'An income source is money arriving on a schedule — a salary, a pension, a regular payment from a tenant. Add one and this page can say what is left once the predictable things are accounted for.';

  @override
  String get bills_income_heading => 'Income';

  @override
  String get bills_income_name => 'Name';

  @override
  String get bills_income_nameHint => 'What you would call it out loud — “Salary”, “Pension”.';

  @override
  String get bills_income_noAccount => 'No account';

  @override
  String get bills_income_restored => 'Income source restored.';

  @override
  String bills_income_retireHeading(String name) {
    return 'Retire $name?';
  }

  @override
  String get bills_income_retireMessage =>
      'It stops counting towards what is expected in new months. You can restore it at any time.';

  @override
  String get bills_income_retired => 'Income source retired.';

  @override
  String get bills_income_retiredHeading => 'Retired income sources';

  @override
  String get bills_income_schedule => 'When it arrives';

  @override
  String get bills_income_total => 'Expected this month';

  @override
  String get bills_income_updated => 'Income source saved.';

  @override
  String get bills_irregularHint =>
      'An irregular bill has no set date. It is listed here, and you record each payment as it happens.';

  @override
  String get bills_kind_FORTNIGHTLY => 'Every other week';

  @override
  String get bills_kind_IRREGULAR => 'Irregular';

  @override
  String get bills_kind_MONTHLY => 'Every month';

  @override
  String get bills_kind_WEEKLY => 'Every week';

  @override
  String get bills_kind_YEARLY => 'Every year';

  @override
  String get bills_kind_unknown => 'On a schedule';

  @override
  String get bills_lede =>
      'The predictable half of your money: what arrives on a schedule, and what leaves on a date. With your envelopes, that is enough to say what is left.';

  @override
  String get bills_left => 'left to spend';

  @override
  String get bills_leftHeading => 'What\'s left this month';

  @override
  String get bills_markDue => 'Mark as due';

  @override
  String get bills_markPaid => 'Mark paid';

  @override
  String get bills_markedDue => 'Marked as due.';

  @override
  String get bills_markedPaid => 'Marked paid.';

  @override
  String get bills_month => 'Month';

  @override
  String get bills_moreActions => 'Correct this';

  @override
  String get bills_name => 'Name';

  @override
  String get bills_nameHint => 'What you would call it out loud — “Rent”, “Phone”.';

  @override
  String get bills_nextMonth => 'Next month';

  @override
  String get bills_noCategory => 'No category';

  @override
  String get bills_noneIrregular =>
      'No set date, so nothing is due in particular. Record a payment when one happens.';

  @override
  String get bills_noneThisMonth => 'Nothing due this month.';

  @override
  String get bills_notCounted => 'not counted this month';

  @override
  String get bills_otherAmount => 'Paid a different amount…';

  @override
  String get bills_outstandingLabel => 'Still to pay';

  @override
  String get bills_overdue => 'Overdue';

  @override
  String get bills_overdueLabel => 'Overdue';

  @override
  String get bills_owed_asOfToday =>
      'These two totals and the count are as they stand today, not as they stood in the month shown — a balance as at a past month would need a day-by-day ledger Budget Owl does not keep.';

  @override
  String get bills_owed_body =>
      'What you owe in all. This is a balance, not a monthly commitment, so it is not taken off what is left — doing that would tell a household owing a year\'s wages that it has nothing to spend this month.';

  @override
  String bills_owed_count(String count) {
    return 'Across $count debts';
  }

  @override
  String get bills_owed_countOne => 'On one debt';

  @override
  String get bills_owed_heading => 'Owed';

  @override
  String get bills_owed_minimums => 'Minimums a month';

  @override
  String bills_owed_mixedCurrency(String currency) {
    return 'The count covers debts in every currency; the two totals are $currency only. They do not describe the same set of debts, because adding currencies together would need a rate this instance may not have.';
  }

  @override
  String get bills_owed_none => 'No debts recorded, so nothing here comes off what is left.';

  @override
  String bills_owed_notInBills(String amount) {
    return '$amount of those minimums is not already a bill row this month, and that is the part taken off what is left. The rest is already counted in your bills.';
  }

  @override
  String get bills_owed_open => 'Open the debt plan';

  @override
  String get bills_owed_total => 'Owed in all';

  @override
  String get bills_paidDate => 'Date paid';

  @override
  String get bills_paidLabel => 'Paid';

  @override
  String get bills_paidOnLabel => 'Paid on';

  @override
  String bills_paidTitle(String name) {
    return 'What did you pay for $name?';
  }

  @override
  String get bills_periodLabel => 'Choose a month';

  @override
  String get bills_previousMonth => 'Previous month';

  @override
  String get bills_recordIt => 'Record it';

  @override
  String get bills_recordPayment => 'Record a payment';

  @override
  String bills_recordTitle(String name) {
    return 'Record a payment for $name';
  }

  @override
  String get bills_restore => 'Restore';

  @override
  String get bills_restored => 'Bill restored.';

  @override
  String get bills_retire => 'Retire';

  @override
  String bills_retireHeading(String name) {
    return 'Retire $name?';
  }

  @override
  String get bills_retireMessage =>
      'It stops being listed in new months and keeps every payment you have already recorded. You can restore it at any time.';

  @override
  String get bills_retired => 'Bill retired.';

  @override
  String get bills_retiredHeading => 'Retired bills';

  @override
  String get bills_retiredNote =>
      'These are not listed in new months, and everything recorded against them is kept.';

  @override
  String bills_said_FORTNIGHTLY(String weekday, String from) {
    return 'Every other $weekday, counting from $from';
  }

  @override
  String get bills_said_IRREGULAR => 'Irregular — no set date';

  @override
  String bills_said_MONTHLY(String day) {
    return 'Every month on day $day';
  }

  @override
  String bills_said_WEEKLY(String weekday) {
    return 'Every $weekday';
  }

  @override
  String bills_said_YEARLY(String date) {
    return 'Every year on $date';
  }

  @override
  String get bills_schedule => 'When it is due';

  @override
  String get bills_short => 'short this month';

  @override
  String get bills_shortNote =>
      'Your income this month does not cover the bills and what you have set aside. Nothing is wrong with the figures — they are telling you something.';

  @override
  String get bills_skip => 'Skip';

  @override
  String get bills_skipped => 'Skipped for this month.';

  @override
  String get bills_source_AUTO_MATCHED => 'Matched automatically';

  @override
  String get bills_source_MANUAL => 'You recorded this';

  @override
  String get bills_source_unknown => 'Recorded';

  @override
  String get bills_status_DUE => 'Due';

  @override
  String get bills_status_PAID => 'Paid';

  @override
  String get bills_status_SKIPPED => 'Skipped';

  @override
  String get bills_status_unknown => 'Recorded';

  @override
  String bills_stillToPayOf(String outstanding, String total) {
    return '$outstanding of $total still to pay';
  }

  @override
  String get bills_surprising => 'Not what was budgeted';

  @override
  String get bills_termBills => 'Bills this month';

  @override
  String get bills_termDebtMinimums => 'Debt minimums';

  @override
  String get bills_termEnvelopes => 'Set aside in envelopes';

  @override
  String get bills_termGoals => 'Goals';

  @override
  String get bills_termIncome => 'Income expected';

  @override
  String get bills_title => 'Bills & income';

  @override
  String get bills_tolerance => 'Give or take';

  @override
  String bills_toleranceHint(String currency) {
    return 'How far from the expected amount is unremarkable, in $currency. A payment further out than that is flagged.';
  }

  @override
  String get bills_totalLabel => 'Bills this month';

  @override
  String get bills_truth_AUTO => 'From your history';

  @override
  String get bills_truth_MANUAL_ESTIMATE => 'Your estimate';

  @override
  String get bills_truth_unknown => 'Estimate';

  @override
  String get bills_updated => 'Bill saved.';

  @override
  String get bills_variesBy => 'Varies by up to';

  @override
  String get categories_add => 'Add a category';

  @override
  String get categories_addChild => 'Add a sub-category';

  @override
  String get categories_addFirst => 'Add your first category';

  @override
  String get categories_addTitle => 'Add a category';

  @override
  String get categories_archive => 'Archive';

  @override
  String categories_archiveHeading(String name) {
    return 'Archive $name?';
  }

  @override
  String get categories_archiveMessage =>
      'It stops appearing when you record a transaction, and your past transactions still show it. You can restore it at any time.';

  @override
  String get categories_archived => 'Category archived.';

  @override
  String get categories_archivedHeading => 'Archived categories';

  @override
  String get categories_archivedNote =>
      'These don\'t appear when you record a transaction, but your past transactions still show them.';

  @override
  String categories_childLabel(String parent, String name) {
    return '$parent → $name';
  }

  @override
  String get categories_created => 'Category added.';

  @override
  String get categories_delete => 'Delete';

  @override
  String categories_deleteHeading(String name) {
    return 'Delete $name?';
  }

  @override
  String get categories_deleteMessage =>
      'Your transactions are kept — they\'ll just need a category again. This can\'t be undone.';

  @override
  String categories_deleteMessageWithChildren(String count) {
    return 'Its $count sub-categories are deleted too. Your transactions are kept — they\'ll just need a category again. This can\'t be undone.';
  }

  @override
  String get categories_deleted => 'Category deleted.';

  @override
  String get categories_editTitle => 'Edit category';

  @override
  String get categories_emptyBody =>
      'Categories let you see where your money goes. You started with a set — if you\'ve deleted them all, add the ones you want.';

  @override
  String get categories_emptyReadOnly => 'Ask an owner or member of your household to add some.';

  @override
  String get categories_hasChildrenHint =>
      'This category has sub-categories, so it can\'t sit under another one.';

  @override
  String get categories_heading => 'Your categories';

  @override
  String categories_hideArchived(String count) {
    return 'Hide $count archived';
  }

  @override
  String get categories_kind => 'Kind';

  @override
  String get categories_kind_EXPENSE => 'Spending';

  @override
  String get categories_kind_INCOME => 'Income';

  @override
  String get categories_kind_unknown => 'Other';

  @override
  String get categories_kindFixedHint =>
      'A category\'s kind can\'t change, because that would reinterpret everything already filed under it.';

  @override
  String get categories_kindFromParentHint =>
      'A sub-category uses the same kind as the category it sits under.';

  @override
  String get categories_lede =>
      'How you group what your money was for. Edit these to match how you actually think about your spending.';

  @override
  String get categories_name => 'Name';

  @override
  String get categories_noParent => 'Nothing — it\'s a top-level category';

  @override
  String get categories_parent => 'Sits under';

  @override
  String get categories_parentHint =>
      'Categories can be two levels deep, like Bills then Utilities.';

  @override
  String categories_showArchived(String count) {
    return 'Show $count archived';
  }

  @override
  String get categories_title => 'Categories';

  @override
  String get categories_unarchive => 'Restore';

  @override
  String get categories_unarchived => 'Category restored.';

  @override
  String get categories_updated => 'Category saved.';

  @override
  String checkIn_account(String name, String lastFour) {
    return '$name •$lastFour';
  }

  @override
  String checkIn_accountNoDigits(String name) {
    return '$name';
  }

  @override
  String get checkIn_answerConflict =>
      'Someone else answered this first. Their answer is shown below.';

  @override
  String checkIn_answeredBy(String name) {
    return 'Answered by $name';
  }

  @override
  String get checkIn_back => 'Back';

  @override
  String get checkIn_bills_amount => 'Amount';

  @override
  String get checkIn_bills_bill => 'Bill';

  @override
  String get checkIn_bills_date => 'Date';

  @override
  String checkIn_bills_due(String date) {
    return 'Due $date';
  }

  @override
  String get checkIn_bills_heading => 'Bills this month';

  @override
  String get checkIn_bills_mapHeading => 'One to map';

  @override
  String get checkIn_bills_overdue => 'Overdue';

  @override
  String get checkIn_bills_paid => 'Paid';

  @override
  String get checkIn_bills_skipped => 'Skipped';

  @override
  String get checkIn_bills_status => 'Status';

  @override
  String get checkIn_bills_stillToPay => 'Still to leave this month';

  @override
  String get checkIn_categorize_always => 'Always file this payee here';

  @override
  String get checkIn_categorize_category => 'Category';

  @override
  String checkIn_categorize_categoryPath(String parent, String name) {
    return '$parent → $name';
  }

  @override
  String get checkIn_categorize_choose => 'Choose a category';

  @override
  String checkIn_categorize_heading(String count) {
    return '$count charges need a category';
  }

  @override
  String get checkIn_categorize_headingOne => '1 charge needs a category';

  @override
  String get checkIn_categorize_headingPlaced => 'Placed by your rules';

  @override
  String checkIn_categorize_more(String count) {
    return 'And $count more in the ledger.';
  }

  @override
  String get checkIn_categorize_moreLink => 'See them all';

  @override
  String checkIn_categorize_placed(String placed, String total) {
    return '$placed of $total placed';
  }

  @override
  String checkIn_categorize_replacesRule(String category) {
    return 'Replaces your rule filing it under $category.';
  }

  @override
  String get checkIn_charge_attach => 'Attach';

  @override
  String get checkIn_charge_attachChosen => 'Attach to this bill';

  @override
  String checkIn_charge_attachedTo(String bill, String date) {
    return 'Attached to $bill, due $date';
  }

  @override
  String get checkIn_charge_differentBill => 'Different bill';

  @override
  String get checkIn_charge_mapIt => 'Yes, map it';

  @override
  String checkIn_charge_matched(String bill) {
    return 'Matched to $bill by your mapping.';
  }

  @override
  String get checkIn_charge_notABill => 'Not a bill';

  @override
  String get checkIn_charge_notABillChosen => 'Not a bill';

  @override
  String checkIn_charge_outside(String amount, String bill, String expected) {
    return 'Charged $amount. Your $bill bill is $expected. Attach it?';
  }

  @override
  String get checkIn_charge_pickBill => 'Choose a bill';

  @override
  String checkIn_charge_recurring(String amount, String account, String bill) {
    return 'A repeating $amount has hit $account three months running. Map it to your $bill bill?';
  }

  @override
  String checkIn_charge_recurringPick(String amount, String account) {
    return 'A repeating $amount has hit $account three months running. Which bill is it?';
  }

  @override
  String get checkIn_charge_remember => 'Remember: this payee in this account pays this bill';

  @override
  String get checkIn_charge_whichBill => 'Which bill';

  @override
  String get checkIn_charge_whichDate => 'Which due date';

  @override
  String checkIn_chipDone(String number) {
    return '$number · done';
  }

  @override
  String checkIn_chipName(String number, String name) {
    return '$number · $name';
  }

  @override
  String checkIn_choice_debt(String debt) {
    return 'Pay down $debt';
  }

  @override
  String checkIn_choice_debtMoves(String debt, String from, String to) {
    return 'Moves $debt from $from to $to.';
  }

  @override
  String checkIn_choice_debtNeverMoves(String amount, String debt) {
    return 'Records a $amount payment to $debt today. Make the payment at your bank — Budget Owl never moves money.';
  }

  @override
  String checkIn_choice_debtNoDate(String debt) {
    return '$debt has no payoff month to move yet.';
  }

  @override
  String checkIn_choice_debtSameMonth(String debt) {
    return 'Doesn\'t move $debt\'s payoff month.';
  }

  @override
  String checkIn_choice_goal(String goal) {
    return 'Put it towards $goal';
  }

  @override
  String checkIn_choice_goalMoves(String goal, String from, String to, String target) {
    return '$goal · $from → $to of $target';
  }

  @override
  String checkIn_choice_goalNeverMoves(String amount, String goal) {
    return 'Records $amount set aside for $goal today. Move the money yourself — Budget Owl never moves money.';
  }

  @override
  String checkIn_choice_rollOver(String amount) {
    return 'Weekly target becomes $amount.';
  }

  @override
  String get checkIn_choice_rollOverLabel => 'Roll it into next week';

  @override
  String get checkIn_choiceName_ATTACH => 'Attach';

  @override
  String get checkIn_choiceName_CATEGORY => 'A category';

  @override
  String get checkIn_choiceName_DEBT => 'A debt';

  @override
  String get checkIn_choiceName_GOAL => 'A goal';

  @override
  String get checkIn_choiceName_LEAVE => 'Left as it is';

  @override
  String get checkIn_choiceName_MARK_PAID => 'Mark paid';

  @override
  String get checkIn_choiceName_NOT_A_BILL => 'Not a bill';

  @override
  String get checkIn_choiceName_PREPAID => 'Prepaid';

  @override
  String get checkIn_choiceName_ROLL_OVER => 'Next week';

  @override
  String get checkIn_choiceName_SKIP => 'Skip this month';

  @override
  String checkIn_chosen(String choice) {
    return 'Chosen: $choice';
  }

  @override
  String get checkIn_clear => 'Clear answer';

  @override
  String get checkIn_completed => 'Check-in done';

  @override
  String checkIn_completedBy(String name, String date) {
    return 'Finished by $name on $date';
  }

  @override
  String get checkIn_completedLede =>
      'Here\'s what it recorded. Each record lives where it always does, and is corrected there.';

  @override
  String get checkIn_discard => 'Discard this draft';

  @override
  String get checkIn_discardMessage =>
      'Every answer in it is deleted. Nothing else changes, because nothing was recorded yet.';

  @override
  String get checkIn_discardTitle => 'Discard this check-in?';

  @override
  String get checkIn_discarded => 'The draft was discarded.';

  @override
  String checkIn_draftOpen(String week) {
    return 'There\'s an unfinished check-in for $week. Finish it or discard it before starting this one.';
  }

  @override
  String get checkIn_draftOpenDiscard => 'Discard it';

  @override
  String get checkIn_draftOpenOpen => 'Open it';

  @override
  String checkIn_eyebrow(String week, String range) {
    return 'Sunday check-in · week $week · $range';
  }

  @override
  String checkIn_eyebrowShort(String week) {
    return 'Sunday check-in · week $week';
  }

  @override
  String get checkIn_finish => 'Finish check-in';

  @override
  String get checkIn_finishMobile => 'Done for the week';

  @override
  String get checkIn_finishing => 'Finishing…';

  @override
  String get checkIn_income_asExpected => 'As expected. Nothing to do.';

  @override
  String checkIn_income_expected(String amount) {
    return 'Your plan expects $amount. Nothing to do.';
  }

  @override
  String get checkIn_income_heading => 'Paycheck landed';

  @override
  String get checkIn_income_headingNotYet => 'Paycheck not seen yet';

  @override
  String checkIn_income_landed(String name, String date, String amount) {
    return '$name · $date · $amount';
  }

  @override
  String checkIn_income_notYet(String name, String date) {
    return '$name: expected $date, not seen yet. Nothing to do.';
  }

  @override
  String get checkIn_keptAsYouGo =>
      'Your answers are kept as you go, on any device. Nothing changes in your budget until you finish.';

  @override
  String get checkIn_leaveIt => 'Leave it';

  @override
  String get checkIn_mapping_exact => 'exact match';

  @override
  String get checkIn_mapping_label => 'Mapped';

  @override
  String get checkIn_mapping_removed =>
      'The mapping was removed. The next check-in will ask about that charge again.';

  @override
  String get checkIn_mapping_unmap => 'Unmap';

  @override
  String checkIn_mapping_varies(String amount) {
    return 'amount varies ±$amount';
  }

  @override
  String checkIn_next_dueNextWeek(String bills) {
    return 'That\'s after $bills, which it already counts.';
  }

  @override
  String checkIn_next_dueOn(String bill, String date) {
    return '$bill on $date';
  }

  @override
  String get checkIn_next_headingFirst => 'Your first weekly figure';

  @override
  String get checkIn_next_headingOnTarget => 'Right on target';

  @override
  String checkIn_next_headingOver(String amount) {
    return 'You went $amount over';
  }

  @override
  String checkIn_next_headingUnder(String amount) {
    return 'You came in $amount under';
  }

  @override
  String get checkIn_next_negative =>
      'That\'s below zero: this month\'s plan has already spent more than it has.';

  @override
  String get checkIn_next_noTarget =>
      'This is your first check-in, so there was no target to measure this week against.';

  @override
  String checkIn_next_perDay(String amount) {
    return '≈ $amount/day';
  }

  @override
  String get checkIn_next_short =>
      'The month is already short, even though this week came in under, so the only place for it is next week.';

  @override
  String checkIn_next_streak(String count) {
    return '$count weeks under target in a row.';
  }

  @override
  String checkIn_next_streakMoved(String count, String from, String to) {
    return '$count weeks under target in a row — that\'s what moved the debt-free date from $from to $to.';
  }

  @override
  String checkIn_next_target(String target, String spent) {
    return 'This week\'s target was $target, and $spent of unplanned spending went out.';
  }

  @override
  String get checkIn_next_where => 'Where should it go?';

  @override
  String get checkIn_next_youCanSpend => 'Next week you can spend';

  @override
  String get checkIn_nextStep_BILLS => 'Next: bills';

  @override
  String get checkIn_nextStep_CATEGORIZE => 'Next: categorize';

  @override
  String get checkIn_nextStep_NEXT_WEEK => 'Next: next week';

  @override
  String get checkIn_nextStep_WHATS_NEW => 'Next: what\'s new';

  @override
  String get checkIn_notStarted => 'Not started yet';

  @override
  String get checkIn_notStartedBody =>
      'This is how the week looks so far. Start the check-in to answer it.';

  @override
  String get checkIn_notStartedReadOnly =>
      'Nobody has started this week\'s check-in yet. You can read it, but only an owner or a member can answer it.';

  @override
  String get checkIn_odd_heading => 'Bigger than usual';

  @override
  String get checkIn_odd_noOffer =>
      'Budget Owl can\'t tell which bill this is, so there\'s nothing to record. It\'s here so you notice it.';

  @override
  String checkIn_odd_prepaid(String multiple, String bill, String skips) {
    return '$multiple times the $bill bill at once? Treat it as prepaid and skip the next $skips.';
  }

  @override
  String checkIn_odd_prepaidOne(String multiple, String bill) {
    return '$multiple times the $bill bill at once? Treat it as prepaid and skip the next one.';
  }

  @override
  String checkIn_odd_usually(String usual) {
    return 'Usually $usual';
  }

  @override
  String checkIn_odd_usuallyBill(String bill, String usual) {
    return 'Your $bill bill is usually $usual';
  }

  @override
  String get checkIn_odd_yesPrepaid => 'Yes, prepaid';

  @override
  String checkIn_otherCurrencies(String count) {
    return '$count transactions this week are in accounts in another currency. A check-in looks only at your base currency.';
  }

  @override
  String get checkIn_overdue_heading => 'Not marked paid';

  @override
  String get checkIn_overdue_markPaid => 'Mark paid';

  @override
  String checkIn_overdue_reason(String bill, String date, String amount) {
    return '$bill was due $date ($amount) and hasn\'t been marked paid.';
  }

  @override
  String get checkIn_overdue_skip => 'Skip this month';

  @override
  String checkIn_progress(String answered, String shown) {
    return '$answered of $shown answered';
  }

  @override
  String checkIn_progressBar(String current, String total) {
    return 'Step $current of $total';
  }

  @override
  String get checkIn_prompt_continue => 'Continue';

  @override
  String get checkIn_prompt_due => 'Due Sunday.';

  @override
  String get checkIn_prompt_label => 'Weekly check-in';

  @override
  String get checkIn_prompt_quiet => 'A few minutes to see where the week went.';

  @override
  String get checkIn_prompt_start => 'Start now';

  @override
  String checkIn_prompt_waiting(String count) {
    return '$count charges waiting on a category.';
  }

  @override
  String get checkIn_prompt_waitingOne => '1 charge waiting on a category.';

  @override
  String checkIn_range(String from, String to) {
    return '$from – $to';
  }

  @override
  String get checkIn_readOnly =>
      'You can read this check-in. Only an owner or a member can answer it.';

  @override
  String checkIn_recordAgainst(String debt) {
    return 'Also record this against $debt';
  }

  @override
  String get checkIn_rules_archived => 'Archived: suggests nothing until you restore the category.';

  @override
  String checkIn_rules_files(String category) {
    return 'files under $category';
  }

  @override
  String get checkIn_rules_hide => 'Hide payee rules';

  @override
  String checkIn_rules_more(String count) {
    return '$count more rules are not shown here.';
  }

  @override
  String get checkIn_rules_payeeBody =>
      'A weekly check-in remembers a payee when you tick \"Always file this payee here\". A rule only suggests: the next check-in arrives with that row already answered, and you can change it.';

  @override
  String get checkIn_rules_payeeEmpty => 'No payee rules yet.';

  @override
  String get checkIn_rules_payeeHeading => 'Payee rules';

  @override
  String get checkIn_rules_remove => 'Remove';

  @override
  String get checkIn_rules_removed => 'The rule was removed.';

  @override
  String checkIn_rules_show(String count) {
    return 'Show payee rules ($count)';
  }

  @override
  String get checkIn_saving => 'Saving…';

  @override
  String checkIn_spent(String amount) {
    return 'Spent $amount this week';
  }

  @override
  String checkIn_spentSoFar(String amount) {
    return 'Spent $amount so far this week';
  }

  @override
  String get checkIn_stale_already_recorded =>
      'This was recorded on Bills & income in the meantime.';

  @override
  String get checkIn_stale_basis_changed =>
      'The week changed while you were answering: new rows, a rule, a bill or a goal. Look it over and finish again.';

  @override
  String get checkIn_stale_bill_inactive => 'That bill has been retired.';

  @override
  String get checkIn_stale_debt_paid_off => 'That debt is paid off.';

  @override
  String get checkIn_stale_goal_archived => 'That goal has been archived.';

  @override
  String get checkIn_stale_limit_reached => 'That goal or debt has as many records as it can hold.';

  @override
  String get checkIn_stale_subject_changed =>
      'This changed in the ledger after you answered. Check it and answer again.';

  @override
  String get checkIn_stale_subject_gone => 'This is no longer part of the week.';

  @override
  String get checkIn_stale_surplus_changed => 'This week\'s surplus changed. Choose again.';

  @override
  String get checkIn_stale_target_archived =>
      'The category you chose has been archived. Choose another.';

  @override
  String get checkIn_stale_transaction_attached_elsewhere =>
      'This charge is now attached to something else.';

  @override
  String get checkIn_stale_unknown => 'This needs another look.';

  @override
  String get checkIn_staleIntro =>
      'Something changed while you were answering, so nothing was recorded. The marked items need another look.';

  @override
  String get checkIn_start => 'Start check-in';

  @override
  String checkIn_startedBy(String name) {
    return 'Started by $name';
  }

  @override
  String get checkIn_status_allHandled => 'All handled';

  @override
  String get checkIn_status_bothHandled => 'Both handled';

  @override
  String get checkIn_status_nothingToAnswer => 'Nothing to answer';

  @override
  String checkIn_status_waiting(String count) {
    return '$count waiting on you';
  }

  @override
  String get checkIn_status_waitingOne => '1 waiting on you';

  @override
  String get checkIn_statusChip_COMPLETED => 'Done';

  @override
  String get checkIn_statusChip_DRAFT => 'In progress';

  @override
  String get checkIn_statusChip_NOT_STARTED => 'Not started';

  @override
  String get checkIn_step_BILLS => 'Bills';

  @override
  String get checkIn_step_CATEGORIZE => 'Categorize';

  @override
  String get checkIn_step_NEXT_WEEK => 'Next week';

  @override
  String get checkIn_step_WHATS_NEW => 'What\'s new';

  @override
  String checkIn_stepNumbered(String number, String name) {
    return '$number · $name';
  }

  @override
  String get checkIn_stepsLabel => 'Steps';

  @override
  String checkIn_summary_attached(String bill) {
    return 'Marked $bill paid with its charge.';
  }

  @override
  String checkIn_summary_attachedRemembered(String bill) {
    return 'Marked $bill paid and remembered the mapping.';
  }

  @override
  String checkIn_summary_byRule(String count) {
    return '$count of them by your rules.';
  }

  @override
  String checkIn_summary_debt(String amount, String debt) {
    return 'Recorded a $amount payment to $debt.';
  }

  @override
  String checkIn_summary_filed(String count) {
    return 'Filed $count charges.';
  }

  @override
  String get checkIn_summary_filedOne => 'Filed 1 charge.';

  @override
  String checkIn_summary_goal(String amount, String goal) {
    return 'Recorded $amount to $goal.';
  }

  @override
  String checkIn_summary_markedPaid(String bill) {
    return 'Marked $bill paid.';
  }

  @override
  String checkIn_summary_nextWeek(String amount) {
    return 'Next week: $amount.';
  }

  @override
  String checkIn_summary_prepaid(String bill) {
    return 'Marked $bill prepaid.';
  }

  @override
  String checkIn_summary_remembered(String count) {
    return 'Remembered $count payees.';
  }

  @override
  String get checkIn_summary_rememberedOne => 'Remembered 1 payee.';

  @override
  String checkIn_summary_rolledOver(String amount) {
    return 'Rolled $amount into next week.';
  }

  @override
  String checkIn_summary_skipped(String bill) {
    return 'Skipped $bill this month.';
  }

  @override
  String checkIn_title(String week) {
    return 'Check-in for week $week';
  }

  @override
  String get checkIn_weekInOneLine => 'Week in one line';

  @override
  String checkIn_whatsNew_heading(String count) {
    return '$count things to look at';
  }

  @override
  String get checkIn_whatsNew_headingOne => 'One thing to look at';

  @override
  String get checkIn_whatsNew_headingQuiet => 'Nothing odd this week';

  @override
  String get checkIn_whatsNew_quiet =>
      'Quiet week otherwise. Nothing else looks out of the ordinary.';

  @override
  String get checkIn_yourRule => 'Your rule';

  @override
  String get common_cancel => 'Cancel';

  @override
  String get common_close => 'Close';

  @override
  String get common_delete => 'Delete';

  @override
  String get common_done => 'Done';

  @override
  String get common_edit => 'Edit';

  @override
  String get common_followDefault => 'Follow the default';

  @override
  String get common_loading => 'Loading…';

  @override
  String get common_next => 'Next';

  @override
  String get common_previous => 'Previous';

  @override
  String get common_retry => 'Try again';

  @override
  String get common_save => 'Save';

  @override
  String get common_saving => 'Saving…';

  @override
  String get common_you => 'You';

  @override
  String get connect_address => 'Server address';

  @override
  String get connect_addressHint =>
      'It starts with https:// — ask whoever set the server up if you are not sure.';

  @override
  String get connect_changeServer => 'Use a different server';

  @override
  String get connect_checking => 'Checking the server…';

  @override
  String get connect_connectedLede => 'This is a Budget Owl server. What would you like to do?';

  @override
  String connect_connectedTo(String host) {
    return 'Connected to $host';
  }

  @override
  String get connect_continue => 'Connect';

  @override
  String get connect_error_busy => 'The server is busy. Try again in a minute.';

  @override
  String get connect_error_certificateUntrusted =>
      'This phone doesn\'t trust the server\'s certificate, so Budget Owl sends nothing to it. If your server uses its own certificate authority, install that authority on this phone, then try again.';

  @override
  String get connect_error_certificateWrongName =>
      'The server\'s certificate is for a different name than the one you entered. Use the name on the certificate, or fix the certificate.';

  @override
  String get connect_error_hostNotFound =>
      'No server answers to that name. Check the spelling, and that this phone is on a network that can reach it.';

  @override
  String get connect_error_notBudgetOwl =>
      'Something answered at that address, but it isn\'t a Budget Owl server.';

  @override
  String get connect_error_transport => 'The connection to the server failed part-way. Try again.';

  @override
  String get connect_error_unreachable =>
      'The server didn\'t answer. It may be switched off, or not reachable from the network this phone is on.';

  @override
  String connect_error_versionMismatch(String server, String app) {
    return 'This server\'s API is version $server, and this app needs version $app. Update whichever is older, then try again.';
  }

  @override
  String get connect_join => 'I have an invitation link';

  @override
  String get connect_lede =>
      'Budget Owl runs on your household\'s own server, and this app talks only to it. Enter the address you open Budget Owl at in a browser.';

  @override
  String get connect_problem_cleartext =>
      'That address starts with http://, so your password and balances would cross the network unencrypted. Budget Owl connects only over HTTPS.';

  @override
  String get connect_problem_cleartextHelp =>
      'The user guide\'s page “Give your server an HTTPS address” shows three ways to do it.';

  @override
  String get connect_problem_credentials =>
      'Take the user name and password out of the address. You sign in on the next screen.';

  @override
  String get connect_problem_empty => 'Enter your server\'s address.';

  @override
  String get connect_problem_invalid => 'That doesn\'t look like a server address.';

  @override
  String get connect_problem_path =>
      'Enter just the server\'s address, with nothing after its name — no /api and no other path.';

  @override
  String get connect_problem_query => 'Enter just the server\'s address, without a ? or # part.';

  @override
  String get connect_setUp => 'Set up a new server';

  @override
  String get connect_setUpHint =>
      'Nobody has an account on this server yet. The first account you create becomes its owner.';

  @override
  String get connect_signIn => 'Sign in with my account';

  @override
  String get connect_title => 'Connect to your server';

  @override
  String get debt_add => 'Add a debt';

  @override
  String get debt_addTitle => 'Add a debt';

  @override
  String get debt_amountSaved => 'Monthly amount saved.';

  @override
  String get debt_arrangeHint =>
      'Move a debt up or down to set the order yourself; the dates are worked out again each time.';

  @override
  String debt_balance_confirmedLine(String source, String amount, String date) {
    return '$source. You last confirmed $amount on $date.';
  }

  @override
  String debt_balance_estimated(String months) {
    return 'An estimate — $months months assumed since you last confirmed it';
  }

  @override
  String get debt_balance_estimatedLabel => 'estimated — not confirmed';

  @override
  String debt_balance_estimatedLine(String months, String amount, String date) {
    return 'An estimate: $months months rolled forward from the $amount you confirmed on $date, assuming the minimum was paid and the interest in between was added.';
  }

  @override
  String get debt_balance_owedNow => 'owed now';

  @override
  String debt_balance_stale(String date) {
    return 'Estimated for over a year. Check a statement — last confirmed $date.';
  }

  @override
  String get debt_balance_staleLabel => 'last confirmed over a year ago';

  @override
  String debt_balance_staleLine(String amount, String date) {
    return 'Estimated for over a year, so this is the $amount you confirmed on $date rather than a year of guesses. A statement figure would put it right.';
  }

  @override
  String get debt_balanceConfirmed =>
      'Balance confirmed. Anything the arithmetic had wrong is recorded as a correction rather than tidied away.';

  @override
  String debt_balanceSource_CONFIRMED(String date) {
    return 'Confirmed $date';
  }

  @override
  String debt_balanceSource_ESTIMATED(String date) {
    return 'An estimate, from $date';
  }

  @override
  String debt_balanceSource_LINKED_STATEMENT(String date) {
    return 'From a statement on $date';
  }

  @override
  String debt_balanceSource_unknown(String date) {
    return 'Balance as at $date';
  }

  @override
  String debt_belowMinimums(String amount, String minimums) {
    return '$amount a month is less than the $minimums your debts ask for as minimums.';
  }

  @override
  String get debt_belowMinimumsNote =>
      'There is no order to build from that, so no dates are shown. Nothing here is a judgement — the figures are yours and this page will work the moment the monthly amount covers the minimums.';

  @override
  String debt_card_debtFree(String month) {
    return 'Debt-free $month';
  }

  @override
  String debt_card_firstWin(String month) {
    return 'First one gone $month';
  }

  @override
  String debt_card_noDate(String reason) {
    return 'No debt-free date, because $reason';
  }

  @override
  String get debt_card_noInterest => 'No interest total, because this order has no end date yet.';

  @override
  String debt_card_totalInterest(String amount) {
    return '$amount of interest along the way';
  }

  @override
  String get debt_changeMonthlyAmount => 'Change the monthly amount';

  @override
  String debt_chart_assumes(String amount) {
    return 'Every line assumes $amount a month.';
  }

  @override
  String get debt_chart_atMinimum => 'At the minimum — the comparison';

  @override
  String get debt_chart_atMinimumNote =>
      'The comparison line assumes the minimum never changes. A card\'s real minimum falls as the balance falls, which stretches it out further still — so the comparison is kinder to the minimum than reality is.';

  @override
  String debt_chart_buying(String extra, String months, String soon, String lateValue) {
    return 'Paying $extra extra is buying you $months months: gone by $soon instead of $lateValue.';
  }

  @override
  String debt_chart_clearsMarker(String month) {
    return 'Gone by $month';
  }

  @override
  String debt_chart_coincide(String lines) {
    return 'One line here stands for all of these orders: $lines. Over this plan they stay closer together than the chart can draw. The cards below give each one\'s own figures.';
  }

  @override
  String debt_chart_detailAssumes(String amount) {
    return 'The first line assumes $amount a month.';
  }

  @override
  String debt_chart_marker(String month) {
    return 'Debt-free $month';
  }

  @override
  String debt_chart_noLine(String reason) {
    return 'There is no line to draw, because $reason.';
  }

  @override
  String get debt_chart_pace => 'What you are paying';

  @override
  String debt_chart_said(String name, String from, String fromMonth, String to, String toMonth) {
    return '$name: $from in $fromMonth, down to $to in $toMonth.';
  }

  @override
  String debt_chart_saidNothing(String name, String reason) {
    return '$name: no line, because $reason.';
  }

  @override
  String debt_chart_stride(String months) {
    return 'Each point stands for $months months.';
  }

  @override
  String get debt_chartHeading => 'Balance to zero';

  @override
  String get debt_clears_never => 'Does not clear — the payment is under the interest';

  @override
  String get debt_clears_noPlan => 'No date — the monthly amount is under the minimums';

  @override
  String debt_clears_on(String month) {
    return 'Clears $month';
  }

  @override
  String get debt_clears_unknown => 'No date yet';

  @override
  String get debt_col_actions => 'Actions';

  @override
  String get debt_col_balance => 'Balance';

  @override
  String get debt_col_clears => 'Clears';

  @override
  String get debt_col_debt => 'Debt';

  @override
  String get debt_col_paying => 'Paying';

  @override
  String get debt_col_progress => 'Progress';

  @override
  String get debt_col_rate => 'Rate';

  @override
  String get debt_col_rung => 'Rung';

  @override
  String get debt_compareHeading => 'Avalanche or snowball';

  @override
  String debt_comparison_both(String cheaper, String sooner, String months) {
    return '$cheaper costs less in interest. $sooner clears your first debt $months months sooner. Either is a good answer.';
  }

  @override
  String debt_comparison_cheaperOnly(String cheaper) {
    return '$cheaper costs less in interest, and both clear your first debt in the same month. Either is a good answer.';
  }

  @override
  String get debt_comparison_same =>
      'Both orders cost the same and finish together, so take whichever you will stick to.';

  @override
  String get debt_created => 'Debt added.';

  @override
  String get debt_detail_back => 'Back to the debt plan';

  @override
  String get debt_detail_balanceHeading => 'Balance now';

  @override
  String get debt_detail_confirmBalance => 'Confirm the balance';

  @override
  String get debt_detail_logPayment => 'Log a payment';

  @override
  String debt_detail_next(String name) {
    return 'Next: $name';
  }

  @override
  String get debt_detail_overTime => 'Balance over time';

  @override
  String debt_detail_previous(String name) {
    return 'Previous: $name';
  }

  @override
  String get debt_detail_switcher => 'Your debts';

  @override
  String get debt_editTerms => 'Edit terms';

  @override
  String get debt_editTitle => 'Edit terms';

  @override
  String get debt_empty_body =>
      'A debt is a balance you are reducing, with terms. Add one and Budget Owl ranks it against the others, works out when each is gone, and shows you what the two usual orders of attack would cost you.';

  @override
  String get debt_empty_counted =>
      'If a debt\'s minimum is already a bill, say which bill — it is then counted once, not twice.';

  @override
  String get debt_empty_heading => 'Nothing owed here yet';

  @override
  String get debt_emptyReadOnly =>
      'You can see the plan, and only an owner or a member can add a debt.';

  @override
  String debt_estimate_assumed(String months, String minimum) {
    return 'That is an estimate. I have assumed $months months of $minimum payments since you last confirmed it, and added the interest in between.';
  }

  @override
  String get debt_estimate_balance => 'What the statement says';

  @override
  String get debt_estimate_balanceDate => 'The date it was true';

  @override
  String get debt_estimate_balanceHint => 'Digits and a dot, like 8900.00.';

  @override
  String debt_estimate_confirmAmount(String amount) {
    return 'Confirm $amount';
  }

  @override
  String get debt_estimate_correct => 'Correct it';

  @override
  String get debt_estimate_correctionNote =>
      'If your figure disagrees with my arithmetic, the difference is recorded as a correction in the history. Nothing already recorded is quietly changed.';

  @override
  String get debt_estimate_figureEstimate => 'my estimate of what is owed now';

  @override
  String get debt_estimate_figureStale => 'the last figure you confirmed';

  @override
  String debt_estimate_lastConfirmed(String amount, String date) {
    return 'Last confirmed: $amount on $date.';
  }

  @override
  String get debt_estimate_logInstead => 'Log a payment instead';

  @override
  String get debt_estimate_save => 'Save the balance';

  @override
  String debt_estimate_staleBody(String months, String amount) {
    return 'It has been $months months with nothing confirmed, so rather than carry a year of guesses forward this shows the $amount you last told me.';
  }

  @override
  String get debt_estimate_staleTitle => 'Time for a statement';

  @override
  String get debt_estimate_stopEstimating => 'Stop estimating — use the last figure I confirmed';

  @override
  String get debt_estimate_title => 'Is this still right?';

  @override
  String debt_extraExceedsLeft(String extra, String left) {
    return 'This plan\'s extra of $extra a month is more than the $left left this month after your bills, envelopes and goals. Nothing changes automatically.';
  }

  @override
  String get debt_field_account => 'Held at';

  @override
  String get debt_field_accountHint =>
      'Optional. A debt you track by hand is ordinary — nothing here needs a bank.';

  @override
  String get debt_field_apr => 'Annual rate';

  @override
  String get debt_field_aprAlreadySolved =>
      'This rate is already worked out from your other figures. Untick to enter one of your own.';

  @override
  String get debt_field_aprHint =>
      'The percentage the statement prints — like 22.9. Zero is a real promotional rate and is accepted.';

  @override
  String get debt_field_aprSolved => 'Work the rate out for me';

  @override
  String get debt_field_aprSolvedHint =>
      'Hands the rate back to Budget Owl, which works it out from your other figures and marks it Solved. Your own figure is removed.';

  @override
  String get debt_field_balance => 'Balance now';

  @override
  String get debt_field_balanceDate => 'The date that balance was true';

  @override
  String get debt_field_balanceDateHint =>
      'Off the statement. The balance and its date travel together, so that later figures can say where they came from.';

  @override
  String debt_field_balanceHint(String currency) {
    return 'In $currency, digits and a dot — like 6180.00.';
  }

  @override
  String get debt_field_bill => 'The bill that pays its minimum';

  @override
  String get debt_field_billHint =>
      'Say which bill and the minimum is counted once. Budget Owl never guesses this from a name or an amount — the figure it decides is what you have left this month.';

  @override
  String get debt_field_currency => 'Currency';

  @override
  String debt_field_currencyDefault(String currency) {
    return 'Your household\'s ($currency)';
  }

  @override
  String get debt_field_currencyHint => 'Totals are worked out per currency, never converted.';

  @override
  String get debt_field_kind => 'Kind';

  @override
  String get debt_field_kindHint => 'A card revolves; an installment loan has a term.';

  @override
  String get debt_field_minimum => 'Minimum payment';

  @override
  String get debt_field_minimumHint =>
      'What the lender asks for each month. It never changes this debt\'s own forecast — it is the floor your plan allocates.';

  @override
  String get debt_field_name => 'What you call it';

  @override
  String get debt_field_nameHint => 'Whatever you would say out loud — “Visa”, “Car loan”.';

  @override
  String get debt_field_noAccount => 'Not an account I track';

  @override
  String get debt_field_noBill => 'No bill covers it';

  @override
  String get debt_field_originalBalance => 'What it started at';

  @override
  String get debt_field_originalBalanceHint =>
      'Optional, and only used to show progress. Left blank, no progress figure is shown rather than a misleading 0%.';

  @override
  String get debt_field_servicer => 'Who you owe it to';

  @override
  String get debt_field_servicerHint => 'Optional. The lender or servicer on the statement.';

  @override
  String debt_footer(String minimums) {
    return 'Minimums · each counted once in Bills & income, so a debt whose minimum is already a bill row is not asked for twice — $minimums';
  }

  @override
  String debt_header(String committed, String strategic) {
    return '$committed a month committed · $strategic of it strategic';
  }

  @override
  String debt_headline(String owed, String month) {
    return '$owed to clear · debt-free $month on this plan';
  }

  @override
  String debt_headline_noDate(String owed, String reason) {
    return '$owed to clear · no debt-free date yet, because $reason';
  }

  @override
  String get debt_history_add => 'Add a past payment';

  @override
  String debt_history_after(String amount) {
    return 'Balance after $amount';
  }

  @override
  String get debt_history_balanceAfter => 'Balance after';

  @override
  String debt_history_caption(String name) {
    return 'Every payment recorded against $name, with how each row got here.';
  }

  @override
  String get debt_history_compounding =>
      'Interest is the balance times the annual rate, divided by twelve, until a statement says otherwise — then the statement wins and the gap is recorded as a correction. It is a simplification you can check with a calculator, and it is not what your lender will charge to the penny.';

  @override
  String debt_history_counts(String logged, String corrections) {
    return '$logged you logged · $corrections from a statement';
  }

  @override
  String get debt_history_date => 'Date';

  @override
  String get debt_history_empty =>
      'Nothing recorded against this one yet. The balance is the figure you confirmed, carried forward.';

  @override
  String get debt_history_heading => 'Payment history';

  @override
  String get debt_history_howItGotHere => 'How it got here';

  @override
  String get debt_history_interest => 'Interest';

  @override
  String get debt_history_paid => 'Paid';

  @override
  String debt_history_paidFrom(String account) {
    return 'Paid from $account';
  }

  @override
  String get debt_history_principal => 'Principal';

  @override
  String debt_history_showAll(String count) {
    return 'Showing the most recent $count. The rest are a page away.';
  }

  @override
  String debt_history_split(String interest, String principal) {
    return '$interest interest · $principal principal';
  }

  @override
  String debt_inCurrency(String currency) {
    return 'In $currency.';
  }

  @override
  String get debt_kind_CREDIT_CARD => 'Credit card';

  @override
  String get debt_kind_INSTALLMENT => 'Installment';

  @override
  String get debt_kind_unknown => 'Debt';

  @override
  String get debt_lede =>
      'Your balances, one order of attack. Minimums come from your bills; the extra comes from what is left after your goals.';

  @override
  String get debt_listHeading => 'Your debts';

  @override
  String get debt_mixedCurrency =>
      'Your debts are in more than one currency, so there is no single debt-free date and no combined interest total. Each currency is worked out on its own — converting would need a rate this instance may not have.';

  @override
  String get debt_monthlyAmount => 'Towards debt each month';

  @override
  String get debt_monthlyAmountHint =>
      'What you put against your debts in all, minimums included. Every date on this page is worked out from it.';

  @override
  String get debt_moveDown => 'Move down';

  @override
  String debt_moveDownNamed(String name) {
    return 'Move down: $name';
  }

  @override
  String get debt_moveUp => 'Move up';

  @override
  String debt_moveUpNamed(String name) {
    return 'Move up: $name';
  }

  @override
  String debt_movedTo(String name, String rung, String ofValue) {
    return '$name is now rung $rung of $ofValue.';
  }

  @override
  String debt_naming(String kind, String servicer) {
    return '$kind · $servicer';
  }

  @override
  String get debt_noProgress => 'No starting balance recorded';

  @override
  String get debt_open => 'Open';

  @override
  String get debt_openAny => 'Open any debt for its terms and payments.';

  @override
  String debt_order(String strategy) {
    return '$strategy order.';
  }

  @override
  String get debt_orderCustom => 'In the order you chose.';

  @override
  String debt_paying_clearsIt(String amount) {
    return '$amount — all that is left to clear it';
  }

  @override
  String debt_paying_minimumOnly(String amount) {
    return '$amount minimum';
  }

  @override
  String debt_paying_minimumPlusExtra(String minimum, String extra) {
    return '$minimum minimum + $extra extra';
  }

  @override
  String debt_paying_minimumUntil(String amount) {
    return 'Minimum $amount a month until the rung above clears';
  }

  @override
  String debt_paying_none(String amount) {
    return '$amount minimum';
  }

  @override
  String get debt_payment_amount => 'Amount paid';

  @override
  String get debt_payment_amountHint => 'Digits and a dot, like 445.00.';

  @override
  String get debt_payment_lede =>
      'Filled in with what your plan puts against this one. Change the amount if you paid something else.';

  @override
  String get debt_payment_noAccount => 'Somewhere I do not track';

  @override
  String get debt_payment_note => 'Note';

  @override
  String get debt_payment_paidDate => 'Date paid';

  @override
  String get debt_payment_paidFrom => 'Paid from';

  @override
  String get debt_payment_paidFromHint =>
      'Which of your accounts the money left. Nothing is recorded against that account and no transaction is created — this is only where a matching charge will be looked for later.';

  @override
  String debt_payment_preview(String interest, String principal, String balance) {
    return '$interest interest · $principal principal · balance after $balance';
  }

  @override
  String get debt_payment_previewUnavailable =>
      'I could not work out the split just now. You can still record the payment — the interest and the principal will be shown on it once it is.';

  @override
  String get debt_payment_record => 'Record it';

  @override
  String debt_payment_recorded(String interest, String principal, String balance) {
    return 'Payment recorded: $interest interest · $principal principal. Balance now $balance.';
  }

  @override
  String get debt_payment_splitNote =>
      'Budget Owl works out how much of this went on interest and how much came off the balance, using the rate in force on the day, and shows you both the moment it is recorded.';

  @override
  String debt_payment_title(String name) {
    return 'Log a payment to $name';
  }

  @override
  String debt_payment_useMore(String amount) {
    return '$amount';
  }

  @override
  String debt_payment_useScheduled(String amount) {
    return '$amount — what is scheduled';
  }

  @override
  String debt_progress(String paid, String percent, String original) {
    return '$paid paid off · $percent% of $original';
  }

  @override
  String get debt_provenance_AUTO_MATCHED => 'Matched automatically';

  @override
  String get debt_provenance_STATEMENT_CORRECTION => 'Statement correction';

  @override
  String get debt_provenance_USER_LOGGED => 'You logged it';

  @override
  String get debt_provenance_unknown => 'Recorded';

  @override
  String get debt_rate_notEntered => 'Rate not entered';

  @override
  String get debt_rate_unavailable => 'Rate not available';

  @override
  String debt_rate_value(String rate) {
    return '$rate%';
  }

  @override
  String get debt_rateReleased =>
      'Terms saved. The rate is now worked out from your other figures and is marked Solved.';

  @override
  String get debt_rationale_AVALANCHE =>
      'Highest rate first, so the money goes where it is costing you most.';

  @override
  String get debt_rationale_SNOWBALL =>
      'Smallest balance first, so the first debt is gone soonest — which some people need to keep going.';

  @override
  String get debt_reason_belowMinimums => 'the monthly amount is less than the minimums';

  @override
  String get debt_reason_neverClears =>
      'the payment is less than the interest this balance builds up, so it grows';

  @override
  String get debt_reason_unknown => 'there is no projection for it';

  @override
  String debt_rung(String rung) {
    return 'Rung $rung';
  }

  @override
  String debt_rungNamed(String rung, String name) {
    return '$rung $name';
  }

  @override
  String get debt_selected => 'In use';

  @override
  String get debt_settledHeading => 'Not in the ladder';

  @override
  String get debt_source_ENTERED => 'You entered this';

  @override
  String get debt_source_FROM_PLAN => 'From your plan';

  @override
  String get debt_source_SOLVED => 'Solved';

  @override
  String get debt_source_unknown => 'Source not known';

  @override
  String get debt_strategy_AVALANCHE => 'Avalanche';

  @override
  String get debt_strategy_CUSTOM => 'Your own order';

  @override
  String get debt_strategy_SNOWBALL => 'Snowball';

  @override
  String get debt_strategy_unknown => 'Your plan';

  @override
  String get debt_strategyHeading => 'Order of attack';

  @override
  String debt_strategyIs(String name) {
    return 'Your plan uses $name.';
  }

  @override
  String get debt_strategyNote =>
      'Changing this changes every date on this page. Both orders are worked out either way, so you can compare before you choose.';

  @override
  String get debt_strategySaved => 'Order of attack saved.';

  @override
  String debt_tableCaption(String strategy) {
    return 'Your debts in $strategy order, with what each one is paying and when it clears.';
  }

  @override
  String get debt_tableCaptionCustom =>
      'Your debts in the order you chose, with what each one is paying and when it clears.';

  @override
  String get debt_term_apr => 'Annual rate';

  @override
  String get debt_term_balance => 'Balance now';

  @override
  String get debt_term_minimum => 'Minimum payment';

  @override
  String get debt_term_monthsLeft => 'Months left';

  @override
  String get debt_term_neverClears => 'Never, at this payment';

  @override
  String get debt_term_noInterestTotal => 'No total — it never clears';

  @override
  String get debt_term_noOriginal => 'Not recorded';

  @override
  String get debt_term_noPayment => 'Not set';

  @override
  String get debt_term_noValue => 'No answer yet';

  @override
  String get debt_term_originalBalance => 'What it started at';

  @override
  String get debt_term_payment => 'What you are paying';

  @override
  String get debt_term_payoffMonth => 'Gone by';

  @override
  String get debt_term_totalInterest => 'Interest still to come';

  @override
  String get debt_terms_balanceNeverSolved =>
      'The balance is never worked out from the other figures. It is the one number you can read off a statement, so it only ever changes when you say so.';

  @override
  String debt_terms_count(String entered, String solved) {
    return '$entered entered · $solved solved.';
  }

  @override
  String get debt_terms_heading => 'Loan terms';

  @override
  String get debt_terms_promise =>
      'Give me any three of the rate, the payment and the horizon and I will work out the fourth. Anything marked Solved is my arithmetic, not your word.';

  @override
  String get debt_title => 'Debt plan';

  @override
  String get debt_updated => 'Terms saved.';

  @override
  String get debt_useAvalanche => 'Use avalanche';

  @override
  String debt_whatIf_months(String step, String month, String months) {
    return 'Adding $step a month would make you debt-free $month — $months months sooner.';
  }

  @override
  String debt_whatIf_noChange(String step, String month) {
    return 'Adding $step a month would not move the date: $month either way.';
  }

  @override
  String debt_whatIf_oneMonth(String step, String month) {
    return 'Adding $step a month would make you debt-free $month — a month sooner.';
  }

  @override
  String debt_yourOrder(String month, String amount) {
    return 'Your own order: debt-free $month, with $amount of interest along the way.';
  }

  @override
  String debt_yourOrder_noDate(String reason) {
    return 'Your own order has no debt-free date, because $reason.';
  }

  @override
  String get deviceLabel_android => 'Phone · Android';

  @override
  String get deviceLabel_ios => 'iPhone · iOS';

  @override
  String devices_browserOn(String browser, String system) {
    return '$browser on $system';
  }

  @override
  String get devices_confirmHeading => 'Sign out of this browser?';

  @override
  String get devices_confirmHeadingPhone => 'Sign out of this phone?';

  @override
  String get devices_confirmMessage =>
      'This is the browser you are using now. You will be signed out and taken to the sign-in screen.';

  @override
  String get devices_confirmMessagePhone =>
      'This is the phone you are using now. You will be signed out, and what Budget Owl keeps on it is erased.';

  @override
  String get devices_empty => 'Nothing is signed in.';

  @override
  String get devices_heading => 'Where you are signed in';

  @override
  String get devices_kindApp => 'Mobile app';

  @override
  String get devices_kindSession => 'Web browser';

  @override
  String devices_lastUsed(String date) {
    return 'Last used $date';
  }

  @override
  String get devices_lede =>
      'Every browser and phone where you are signed in. Sign one out and it stops working immediately.';

  @override
  String get devices_revoked => 'That device has been signed out.';

  @override
  String get devices_signOut => 'Sign out';

  @override
  String get devices_signOutHere => 'Sign out here';

  @override
  String devices_signedInAt(String date) {
    return 'Signed in $date';
  }

  @override
  String get devices_thisDevice => 'This device';

  @override
  String get devices_thisPhone => 'This phone';

  @override
  String get devices_title => 'Devices';

  @override
  String get duration_hour => '1 hour';

  @override
  String duration_hours(String count) {
    return '$count hours';
  }

  @override
  String get duration_minute => '1 minute';

  @override
  String duration_minutes(String count) {
    return '$count minutes';
  }

  @override
  String duration_seconds(String count) {
    return '$count seconds';
  }

  @override
  String get envelopes_add => 'Add an envelope';

  @override
  String get envelopes_addFirst => 'Add your first envelope';

  @override
  String get envelopes_addTitle => 'Add an envelope';

  @override
  String get envelopes_amount => 'Amount';

  @override
  String envelopes_amountHint(String currency) {
    return 'In $currency.';
  }

  @override
  String get envelopes_archive => 'Archive';

  @override
  String envelopes_archiveHeading(String name) {
    return 'Archive $name?';
  }

  @override
  String get envelopes_archiveMessage =>
      'It stops taking new entries and drops out of this month\'s list. Everything you\'ve logged against it is kept. You can restore it at any time.';

  @override
  String get envelopes_archived => 'Envelope archived.';

  @override
  String get envelopes_archivedHeading => 'Archived envelopes';

  @override
  String get envelopes_archivedNote =>
      'These don\'t take new entries, but what you logged against them is still here.';

  @override
  String get envelopes_category => 'Category';

  @override
  String get envelopes_created => 'Envelope added.';

  @override
  String get envelopes_date => 'Date';

  @override
  String get envelopes_deleteEntryHeading => 'Remove this entry?';

  @override
  String envelopes_deleteEntryMessage(String name) {
    return 'The amount goes back to $name. If it came from a transaction, the transaction itself is kept.';
  }

  @override
  String envelopes_deleteHeading(String name) {
    return 'Delete $name?';
  }

  @override
  String envelopes_deleteMessage(String count) {
    return 'Its $count logged entries go with it. Your transactions are kept — only the envelope\'s own record of them is removed. This can\'t be undone.';
  }

  @override
  String get envelopes_deleted => 'Envelope deleted.';

  @override
  String get envelopes_editEntryTitle => 'Edit this entry';

  @override
  String get envelopes_editTitle => 'Edit envelope';

  @override
  String get envelopes_emptyBody =>
      'An envelope is an amount you decide on and spend down — groceries, eating out, fuel. Set one up and you\'ll see what\'s left as you go.';

  @override
  String get envelopes_emptyReadOnly => 'Ask an owner or member of your household to set some up.';

  @override
  String get envelopes_entryDeleted => 'Entry removed.';

  @override
  String get envelopes_entryUpdated => 'Entry saved.';

  @override
  String get envelopes_heading => 'Your envelopes';

  @override
  String envelopes_hideArchived(String count) {
    return 'Hide $count archived';
  }

  @override
  String get envelopes_hideLog => 'Hide entries';

  @override
  String get envelopes_lede =>
      'Money you\'ve set aside for the spending that changes week to week. Log what you spend and see what\'s left, straight away.';

  @override
  String get envelopes_left => 'left';

  @override
  String get envelopes_logEmpty => 'Nothing logged against this envelope yet.';

  @override
  String get envelopes_logIt => 'Log it';

  @override
  String get envelopes_logSpend => 'Log spend';

  @override
  String envelopes_logTitle(String envelope) {
    return 'Log spend in $envelope';
  }

  @override
  String get envelopes_logged => 'Logged.';

  @override
  String envelopes_meterLabel(String name, String percent) {
    return '$name: $percent% of the amount spent';
  }

  @override
  String get envelopes_month => 'Month';

  @override
  String get envelopes_monthFixedHint =>
      'An envelope stays in the month it was set for. Make a new one for another month.';

  @override
  String get envelopes_name => 'Name';

  @override
  String get envelopes_nameHint =>
      'Keep them narrow — “Groceries” and “Eating out” are two envelopes, not one.';

  @override
  String get envelopes_nextMonth => 'Next month';

  @override
  String get envelopes_noCategory => 'No category';

  @override
  String get envelopes_note => 'Note';

  @override
  String get envelopes_optional => 'Optional.';

  @override
  String get envelopes_over => 'over';

  @override
  String get envelopes_payee => 'Who it was with';

  @override
  String get envelopes_periodLabel => 'Choose a month';

  @override
  String get envelopes_previousMonth => 'Previous month';

  @override
  String get envelopes_runningTotal => 'Spent so far';

  @override
  String envelopes_showArchived(String count) {
    return 'Show $count archived';
  }

  @override
  String envelopes_showLog(String count) {
    return 'Show $count entries';
  }

  @override
  String get envelopes_showLogOne => 'Show 1 entry';

  @override
  String get envelopes_source_MANUAL => 'Typed';

  @override
  String get envelopes_source_SYNCED => 'From a transaction';

  @override
  String get envelopes_source_unknown => 'Other';

  @override
  String get envelopes_title => 'Envelopes';

  @override
  String get envelopes_totalAllocated => 'Set aside';

  @override
  String get envelopes_totalRemaining => 'Left';

  @override
  String get envelopes_totalSpent => 'Spent';

  @override
  String get envelopes_unarchive => 'Restore';

  @override
  String get envelopes_unarchived => 'Envelope restored.';

  @override
  String get envelopes_updated => 'Envelope saved.';

  @override
  String get error_account_archived =>
      'That account is archived, so nothing new can be recorded against it. Restore it first — what\'s already recorded is kept.';

  @override
  String get error_account_currency_immutable =>
      'This account\'s currency cannot be changed now that it has transactions. The amounts already recorded would mean something different.';

  @override
  String get error_account_not_empty =>
      'This account has transactions recorded against it, so it cannot be deleted. Archive it instead — it keeps its history and disappears from your lists.';

  @override
  String get error_authentication_failed => 'The email address or password is not right.';

  @override
  String get error_bill_inactive =>
      'This bill is retired, so nothing new can be recorded against it. Restore it first — what was already recorded is kept.';

  @override
  String get error_bill_name_taken => 'You already have a bill with that name.';

  @override
  String get error_charge_rule_limit_reached =>
      'This household has as many bill mappings as it can hold. Unmap one on Bills & income first.';

  @override
  String get error_check_in_completed =>
      'This check-in is already done. A finished check-in can\'t be changed.';

  @override
  String get error_check_in_draft_open =>
      'There\'s already an unfinished check-in for another week. Finish or discard it first.';

  @override
  String get error_check_in_incomplete =>
      'Choose where this week\'s surplus goes before you finish.';

  @override
  String get error_check_in_stale =>
      'Something changed while you were answering, so nothing was recorded. Look the week over and finish again.';

  @override
  String get error_check_in_week_out_of_range =>
      'That week is too far back or too far ahead for a check-in.';

  @override
  String get error_conflict =>
      'That conflicts with a change made elsewhere. Reload the page and try again.';

  @override
  String get error_csrf_token_required =>
      'Your session could not be verified. Reload the page and try again.';

  @override
  String get error_current_password_incorrect => 'The current password is not right.';

  @override
  String get error_forbidden => 'You do not have permission to do that.';

  @override
  String get error_generic => 'Something went wrong on our side. Try again in a moment.';

  @override
  String get error_goal_archived =>
      'This goal is archived. Restore it to set money aside or change its plan.';

  @override
  String get error_goal_currency_mismatch =>
      'That account is in a different currency from this goal, so it cannot hold it.';

  @override
  String get error_goal_limit_reached =>
      'This household has as many goals, or this goal as many records, as one household can hold. Archive or delete one first.';

  @override
  String get error_goal_name_taken =>
      'You already have a goal with this name. Choose a different one.';

  @override
  String get error_goal_plan_period_past =>
      'The plan can only change from this month on. To correct a past month, record or remove what was set aside then.';

  @override
  String get error_goal_plan_period_too_far =>
      'The plan can only change up to two years ahead. Choose an earlier month.';

  @override
  String get error_import_content_encoding_refused =>
      'That upload was compressed on the way, which Budget Owl doesn\'t accept. Reload the page and try again.';

  @override
  String get error_import_currency_mismatch =>
      'That statement is in a different currency from the account you chose.';

  @override
  String get error_import_encoding_unsupported =>
      'Budget Owl can\'t read that character set. Choose another one.';

  @override
  String get error_import_file_empty => 'That file is empty.';

  @override
  String get error_import_file_malformed => 'That file can\'t be read as a bank statement.';

  @override
  String get error_import_file_malformed_binary =>
      'That file isn\'t text. Check you chose the statement your bank exported, not a spreadsheet or a PDF.';

  @override
  String get error_import_file_malformed_compressed =>
      'That file is compressed. Unzip it and choose the statement inside.';

  @override
  String get error_import_file_malformed_dtd =>
      'That file contains a document type declaration. Bank statements don\'t need one, and Budget Owl doesn\'t read them.';

  @override
  String get error_import_file_malformed_element_too_long =>
      'One value in that file is far longer than a statement\'s.';

  @override
  String get error_import_file_malformed_encrypted =>
      'That file is password-protected. Export it again without a password.';

  @override
  String get error_import_file_malformed_header =>
      'The start of that file isn\'t a valid OFX header.';

  @override
  String get error_import_file_malformed_line_too_long =>
      'One line in that file is far longer than a statement\'s.';

  @override
  String get error_import_file_malformed_not_a_statement =>
      'That file doesn\'t contain a statement Budget Owl can read.';

  @override
  String get error_import_file_malformed_too_deep =>
      'That file is nested more deeply than any bank statement.';

  @override
  String get error_import_file_malformed_too_many_columns =>
      'That file has more columns than any statement Budget Owl reads.';

  @override
  String get error_import_file_malformed_too_many_elements =>
      'That file has more parts than any statement Budget Owl reads.';

  @override
  String get error_import_file_malformed_unclosed_quote =>
      'That file has a quote that\'s never closed, so its columns can\'t be read.';

  @override
  String get error_import_file_too_complex =>
      'That file took too long to read. Export a shorter date range and try again.';

  @override
  String get error_import_file_too_large =>
      'That file is larger than Budget Owl reads at once (5 MB). Export a shorter date range and import it in parts. If the file is smaller than that, the server in front of Budget Owl has a lower limit; ask whoever runs it.';

  @override
  String get error_import_format_mismatch =>
      'That file isn\'t in the format you chose. Check the Format setting.';

  @override
  String get error_import_multiple_accounts =>
      'That file holds statements for more than one account. Export one account at a time.';

  @override
  String get error_import_preview_stale =>
      'Your ledger changed since the preview, so nothing was imported. Preview the file again.';

  @override
  String get error_import_too_many_rows =>
      'That file has more than 10,000 transactions, the most Budget Owl reads at once. Export a shorter date range and import it in parts.';

  @override
  String get error_income_name_taken => 'You already have an income source with that name.';

  @override
  String get error_internal_error => 'Something went wrong on our side. Try again in a moment.';

  @override
  String get error_invitation_refused =>
      'An invitation could not be created for that email address.';

  @override
  String get error_invitation_requires_sign_out =>
      'You are signed in. Sign out before accepting an invitation.';

  @override
  String get error_invitation_unusable =>
      'This invitation link cannot be used. It may have expired, been used already, or been cancelled. Ask the person who invited you for a new one.';

  @override
  String get error_last_owner =>
      'A household must keep at least one owner. Make someone else an owner first.';

  @override
  String get error_malformed_request =>
      'That request could not be understood. Reload the page and try again.';

  @override
  String get error_network =>
      'Budget Owl could not be reached. Check that the server is running and that you are connected, then try again.';

  @override
  String get error_not_a_member => 'You are not a member of this household.';

  @override
  String get error_not_authenticated => 'You are not signed in. Sign in and try again.';

  @override
  String get error_not_found => 'That could not be found. It may have been removed.';

  @override
  String get error_not_instance_admin =>
      'Only the person who administers this instance can do that. On a self-hosted Budget Owl that is whoever set it up.';

  @override
  String get error_own_role_unchangeable => 'Nobody can change their own role. Ask another owner.';

  @override
  String get error_owner_only => 'Only an owner of the household can do that.';

  @override
  String get error_password_login_locked =>
      'Email and password sign-in cannot be turned off yet. An owner has to complete a sign-in through your provider first, which proves the new way in works before the old one is hidden.';

  @override
  String get error_password_unacceptable => 'That password cannot be used. Choose a different one.';

  @override
  String get error_password_unacceptable_breached =>
      'That password appears on a list of commonly used passwords. Choose a different one.';

  @override
  String error_password_unacceptable_too_long(String maximumBytes) {
    return 'That password is too long. Use at most $maximumBytes bytes.';
  }

  @override
  String error_password_unacceptable_too_short(String minimumLength) {
    return 'That password is too short. Use at least $minimumLength characters.';
  }

  @override
  String get error_payee_rule_limit_reached =>
      'This household has as many payee rules as it can hold. Remove one on the categories page first.';

  @override
  String error_rate_limited(String minutes) {
    return 'Too many attempts. Wait $minutes and try again.';
  }

  @override
  String get error_read_only_role => 'Your role in this household is read-only.';

  @override
  String error_reference(String id) {
    return 'Reference: $id';
  }

  @override
  String get error_report_range_invalid =>
      'That range of months doesn\'t work. Choose a start month on or before the end month.';

  @override
  String error_report_range_too_long(String maximumMonths) {
    return 'Insights can show at most $maximumMonths months at a time.';
  }

  @override
  String get error_setup_already_complete =>
      'This instance has already been set up. Sign in instead.';

  @override
  String get error_stale_version =>
      'Somebody else changed this while you were editing it. Nothing has been saved.';

  @override
  String get error_transfer_leg_in_envelope =>
      'One of these rows is logged against an envelope. Moving money between your own accounts isn\'t spending, so take it out of the envelope first, then pair the rows.';

  @override
  String get error_transfer_leg_not_separate =>
      'This is one half of a transfer, and the two halves are always changed together. Edit or delete the transfer itself.';

  @override
  String get error_transfer_pair_mismatch => 'Those two rows can\'t be one transfer.';

  @override
  String get error_transfer_pair_mismatch_amounts_differ =>
      'The two amounts aren\'t equal. In one currency, what left one account has to be what arrived in the other.';

  @override
  String get error_transfer_pair_mismatch_same_account =>
      'Both rows are in the same account. A transfer moves money between two different accounts.';

  @override
  String get error_transfer_pair_mismatch_same_sign =>
      'Both rows went the same way. A transfer needs money leaving one account and arriving in the other.';

  @override
  String get error_validation_failed =>
      'Some of what you entered is not valid. Check the highlighted fields.';

  @override
  String get goals_add => 'Add a goal';

  @override
  String get goals_archive_action => 'Archive';

  @override
  String get goals_archive_body =>
      'Its history stays, and it stops being part of the month\'s plan. Anything it planned or recorded this month stops coming off what\'s left to spend, so that figure goes up.';

  @override
  String get goals_archive_confirm => 'Archive';

  @override
  String get goals_archive_done => 'The goal is archived.';

  @override
  String goals_archive_heading(String name) {
    return 'Archive $name?';
  }

  @override
  String get goals_archived_body =>
      'An archived goal keeps its history but is no longer part of the month\'s plan, so nothing it planned or recorded comes off what\'s left to spend.';

  @override
  String get goals_archived_heading => 'Archived goals';

  @override
  String get goals_archived_hide => 'Hide archived goals';

  @override
  String get goals_archived_show => 'Show archived goals';

  @override
  String get goals_archivedTag => 'Archived';

  @override
  String get goals_back => '← All goals';

  @override
  String get goals_contribute_amount => 'Amount';

  @override
  String get goals_contribute_date => 'Date';

  @override
  String get goals_contribute_inSave => 'Set aside';

  @override
  String goals_contribute_inTitle(String name) {
    return 'Set money aside for $name';
  }

  @override
  String get goals_contribute_noLedger =>
      'This records it against the goal only. Nothing is added to or taken from any account — move the money at your bank as usual.';

  @override
  String get goals_contribute_note => 'Note (optional)';

  @override
  String get goals_contribute_outSave => 'Take out';

  @override
  String goals_contribute_outTitle(String name) {
    return 'Take money out of $name';
  }

  @override
  String goals_contribute_prefilled(String month, String amount) {
    return 'Filled in with what is still to come for $month: $amount. Change it for a top-up.';
  }

  @override
  String get goals_contribute_recordedIn => 'Money set aside.';

  @override
  String get goals_contribute_recordedOut => 'Money taken out.';

  @override
  String get goals_countsTowardsLeft =>
      'Each goal\'s monthly amount is taken off what\'s left to spend this month, on Bills & income. It counts in full whether or not you have set it aside yet.';

  @override
  String goals_created(String name) {
    return '$name is added.';
  }

  @override
  String goals_currencyGroup(String currency) {
    return 'Goals in $currency';
  }

  @override
  String get goals_delete_action => 'Delete';

  @override
  String get goals_delete_body =>
      'This deletes the goal, its plan and everything recorded against it, permanently. No transaction is touched. To keep the history, archive it instead.';

  @override
  String get goals_delete_confirm => 'Delete permanently';

  @override
  String goals_delete_heading(String name) {
    return 'Delete $name permanently?';
  }

  @override
  String goals_detail_accountBalance(String account, String amount) {
    return '$account has $amount. That is the account\'s balance, not this goal\'s.';
  }

  @override
  String get goals_detail_actions => 'Actions';

  @override
  String goals_detail_alreadySaved(String amount) {
    return 'Already saved when you started tracking it: $amount';
  }

  @override
  String get goals_detail_amount => 'Amount';

  @override
  String goals_detail_by(String month) {
    return 'By $month';
  }

  @override
  String get goals_detail_date => 'Date';

  @override
  String get goals_detail_exactly => 'Reached, exactly.';

  @override
  String get goals_detail_history => 'History';

  @override
  String get goals_detail_historyCaption =>
      'Money set aside for this goal and taken out of it, newest first.';

  @override
  String goals_detail_keptIn(String account) {
    return 'Kept in $account';
  }

  @override
  String goals_detail_monthly(String amount, String month) {
    return '$amount a month in $month.';
  }

  @override
  String get goals_detail_noHistory => 'Nothing recorded yet.';

  @override
  String get goals_detail_noMonth => 'No target month';

  @override
  String get goals_detail_note => 'Note';

  @override
  String goals_detail_over(String amount) {
    return 'Reached, with $amount more than the target.';
  }

  @override
  String get goals_detail_plan => 'The plan';

  @override
  String get goals_detail_progress => 'How far along';

  @override
  String get goals_detail_remove => 'Remove';

  @override
  String get goals_detail_showMore => 'Show more';

  @override
  String goals_detail_step(String amount, String month) {
    return '$amount a month from $month.';
  }

  @override
  String goals_detail_thisMonth(String month, String planned, String recorded) {
    return 'For $month: $planned planned, $recorded recorded so far.';
  }

  @override
  String goals_detail_toGo(String amount) {
    return '$amount to go.';
  }

  @override
  String get goals_detail_withdrawal => 'Withdrawal';

  @override
  String get goals_dialog_addTitle => 'Add a goal';

  @override
  String get goals_dialog_editTitle => 'Edit goal';

  @override
  String get goals_empty_body =>
      'A goal is money you put aside for something — an emergency fund, a trip, a deposit. Give it a name and how much it needs, with a month if you have one, and see it fill up.';

  @override
  String get goals_empty_heading => 'Nothing you are saving for yet';

  @override
  String get goals_emptyReadOnly =>
      'Your role is read-only, so goals are added by an owner or a member.';

  @override
  String get goals_field_account => 'Where it is kept (optional)';

  @override
  String get goals_field_accountHint =>
      'For your reference. The goal\'s figure comes from what you record here, because one account often holds several goals.';

  @override
  String get goals_field_alreadySaved => 'Already saved (optional)';

  @override
  String get goals_field_alreadySavedHint =>
      'Money you had already put by before you started tracking it. It counts towards the goal and never towards any month\'s left-over figure.';

  @override
  String get goals_field_currency => 'Currency';

  @override
  String goals_field_currencyDefault(String currency) {
    return 'Your household\'s currency ($currency)';
  }

  @override
  String get goals_field_currencyHint =>
      'Fixed once the goal is added. A goal kept in an account is in that account\'s currency.';

  @override
  String get goals_field_month => 'Month you want it by (optional)';

  @override
  String get goals_field_monthHint =>
      'Leave it empty for a goal with no date, like an emergency fund.';

  @override
  String get goals_field_monthly => 'Each month (optional)';

  @override
  String get goals_field_monthlyHint =>
      'What you plan to set aside every month, from this month. It comes off what\'s left to spend.';

  @override
  String get goals_field_name => 'What it is for';

  @override
  String get goals_field_noAccount => 'Nowhere in particular';

  @override
  String get goals_field_target => 'How much it needs';

  @override
  String get goals_lede =>
      'Money you are putting aside for something. What you plan to set aside each month comes off what\'s left to spend.';

  @override
  String get goals_listLabel => 'Your goals';

  @override
  String get goals_noInterest => 'This assumes no interest or growth: a goal is what you put in.';

  @override
  String get goals_open => 'Open →';

  @override
  String get goals_overdueTag => 'Overdue';

  @override
  String get goals_plan_amount => 'Each month';

  @override
  String get goals_plan_amountHint => 'Zero pauses the goal.';

  @override
  String get goals_plan_change => 'Change';

  @override
  String get goals_plan_from => 'Starting';

  @override
  String goals_plan_fromNext(String month) {
    return 'Next month, $month';
  }

  @override
  String goals_plan_fromThis(String month) {
    return 'This month, $month';
  }

  @override
  String get goals_plan_save => 'Save the plan';

  @override
  String goals_plan_saved(String month) {
    return 'The plan is changed from $month.';
  }

  @override
  String goals_plan_thisMonthUnchanged(String month) {
    return '$month stays as it is. The new amount applies from the month after.';
  }

  @override
  String goals_plan_title(String name) {
    return 'Change the plan for $name';
  }

  @override
  String get goals_previewUnavailable =>
      'The figures could not be worked out just now. You can still save.';

  @override
  String goals_projection_late(String monthly, String month, String count, String target) {
    return 'At $monthly a month you reach it in $month — $count months after $target.';
  }

  @override
  String goals_projection_lateOne(String monthly, String month, String target) {
    return 'At $monthly a month you reach it in $month — 1 month after $target.';
  }

  @override
  String goals_projection_onTime(String monthly, String month, String target) {
    return 'At $monthly a month you reach it in $month, in time for $target.';
  }

  @override
  String goals_projection_overdue(String target) {
    return 'Overdue since $target.';
  }

  @override
  String goals_projection_overdueReaches(String target, String monthly, String month) {
    return 'Overdue since $target — at $monthly a month you reach it in $month.';
  }

  @override
  String goals_projection_planLate(String month, String count, String target) {
    return 'Keeping to its plan, you reach it in $month — $count months after $target.';
  }

  @override
  String goals_projection_planLateOne(String month, String target) {
    return 'Keeping to its plan, you reach it in $month — 1 month after $target.';
  }

  @override
  String goals_projection_planOnTime(String month, String target) {
    return 'Keeping to its plan, you reach it in $month, in time for $target.';
  }

  @override
  String goals_projection_planOverdueReaches(String target, String month) {
    return 'Overdue since $target — keeping to its plan, you reach it in $month.';
  }

  @override
  String goals_projection_planReaches(String month) {
    return 'Keeping to its plan, you reach it in $month.';
  }

  @override
  String goals_projection_reaches(String monthly, String month) {
    return 'At $monthly a month you reach it in $month.';
  }

  @override
  String goals_projection_required(String amount, String target) {
    return '$amount a month gets you there by $target.';
  }

  @override
  String get goals_reachedTag => 'Reached';

  @override
  String goals_reason_beyondHorizon(String monthly) {
    return 'At $monthly a month it is more than fifty years away.';
  }

  @override
  String get goals_reason_noContribution => 'No monthly amount set.';

  @override
  String get goals_reason_noTargetMonth => 'There is no target month.';

  @override
  String goals_reason_notReachable(String target) {
    return 'This month started with the goal reached, so this month\'s plan no longer counts. Record the rest directly to make $target.';
  }

  @override
  String get goals_reason_reached => 'Reached.';

  @override
  String get goals_reason_targetMonthPassed => 'The month you picked has passed.';

  @override
  String get goals_removeContribution_body =>
      'The goal\'s figure changes to match. Nothing in any account is touched.';

  @override
  String get goals_removeContribution_confirm => 'Remove';

  @override
  String get goals_removeContribution_done => 'Removed.';

  @override
  String goals_removeContribution_heading(String name) {
    return 'Remove this from $name?';
  }

  @override
  String get goals_required_beyondHorizon =>
      'The month you picked is more than fifty years away, so there is no monthly figure for it.';

  @override
  String get goals_required_noContribution => 'Set a monthly amount to see when you get there.';

  @override
  String get goals_required_noTargetMonth =>
      'There is no target month, so there is no monthly figure to aim for.';

  @override
  String get goals_required_targetMonthPassed =>
      'The month you picked has passed, so there is no monthly figure to reach it by then.';

  @override
  String goals_row_nameAndMonth(String name, String month) {
    return '$name, $month';
  }

  @override
  String goals_row_savedOf(String saved, String target) {
    return '$saved of $target';
  }

  @override
  String get goals_setAside => 'Set money aside';

  @override
  String get goals_takeOut => 'Take money out';

  @override
  String get goals_title => 'Goals';

  @override
  String get goals_unarchive => 'Restore';

  @override
  String get goals_unarchived => 'The goal is restored, and is part of the month\'s plan again.';

  @override
  String get goals_updated => 'The goal is updated.';

  @override
  String get household_baseCurrency => 'Base currency';

  @override
  String get household_baseCurrencyHint =>
      'Totals are shown in this currency unless a member chooses another in their preferences.';

  @override
  String get household_detailsHeading => 'Details';

  @override
  String get household_lede =>
      'The household is the shared pot of money everyone here sees. This instance holds one.';

  @override
  String get household_name => 'Household name';

  @override
  String get household_ownerOnlyEdit => 'Only an owner can change these.';

  @override
  String get household_saved => 'Household saved.';

  @override
  String get household_title => 'Household';

  @override
  String get import_account => 'Account';

  @override
  String import_accountDiffers(String account) {
    return 'This file looks like it belongs to a different account from $account. Check you chose the right one before importing.';
  }

  @override
  String get import_another => 'Import another file';

  @override
  String get import_attention => 'Needs a look';

  @override
  String get import_attentionTruncated =>
      'There are more than are shown here. The counts above include all of them.';

  @override
  String get import_balanceAfter => 'After this import';

  @override
  String get import_balanceAfterWithSuspected => 'If you include every one that looks typed';

  @override
  String get import_balanceDiffers =>
      'Those two differ. That\'s usual when your opening balance or older transactions in Budget Owl don\'t match the bank\'s. Worth checking before you rely on either.';

  @override
  String get import_balanceMatches => 'They match.';

  @override
  String get import_balanceNow => 'Balance in Budget Owl now';

  @override
  String import_bankSays(String bank, String after) {
    return 'Your bank says the balance is $bank. After this import, Budget Owl will say $after.';
  }

  @override
  String get import_cellSays => 'It says:';

  @override
  String get import_charset => 'Character set';

  @override
  String get import_charsetAuto => 'Work it out from the file';

  @override
  String get import_charsetHint =>
      'The file doesn\'t say which character set it uses. Choose the one where names and accents read correctly.';

  @override
  String get import_charsetQuestion => 'Does this text look right?';

  @override
  String import_charsetSamples(String charset) {
    return 'Read as $charset';
  }

  @override
  String get import_chooseAccount => 'Choose an account';

  @override
  String get import_chooseFormat => 'Choose a format';

  @override
  String import_chosenFile(String size) {
    return 'Selected: $size.';
  }

  @override
  String import_column(String number) {
    return 'Column $number';
  }

  @override
  String import_columnLabelled(String number, String label) {
    return 'Column $number · $label';
  }

  @override
  String import_columnRole(String number) {
    return 'What column $number holds';
  }

  @override
  String get import_columns => 'Columns';

  @override
  String get import_columnsCaption => 'The first rows of your file, with what each column holds';

  @override
  String get import_committing => 'Importing…';

  @override
  String import_confirm(String count, String account) {
    return 'Import $count transactions into $account';
  }

  @override
  String get import_count_duplicate => 'Already in your ledger';

  @override
  String get import_count_new => 'New';

  @override
  String get import_count_suspected => 'Look like ones you typed';

  @override
  String get import_count_unreadable => 'Couldn\'t be read';

  @override
  String get import_count_zero => 'For zero, skipped';

  @override
  String get import_dateFormat => 'Date format';

  @override
  String get import_dateFormat_compact => 'Year, month and day together (20260803)';

  @override
  String get import_dateFormat_dmy => 'Day/month/year (3/8/2026)';

  @override
  String get import_dateFormat_dmyDashes => 'Day-month-year (03-08-2026)';

  @override
  String get import_dateFormat_dmyDots => 'Day.month.year (03.08.2026)';

  @override
  String get import_dateFormat_dmyPadded => 'Day/month/year (03/08/2026)';

  @override
  String get import_dateFormat_dmyShort => 'Day/month/two-digit year (3/8/26)';

  @override
  String get import_dateFormat_mdy => 'Month/day/year (8/3/2026)';

  @override
  String get import_dateFormat_mdyPadded => 'Month/day/year (08/03/2026)';

  @override
  String get import_dateFormat_mdyQuicken => 'Quicken style (8/3\'26)';

  @override
  String get import_dateFormat_mdyShort => 'Month/day/two-digit year (8/3/26)';

  @override
  String get import_dateFormat_ymd => 'Year-month-day (2026-08-03)';

  @override
  String get import_dateFormatAmbiguous =>
      'The dates in this file can be read more than one way. Choose how your bank writes them.';

  @override
  String get import_dateFormatChoose => 'Choose a date format';

  @override
  String get import_dateFormatHint => 'Choose how your bank writes dates.';

  @override
  String import_dateRange(String from, String to) {
    return '$from to $to';
  }

  @override
  String get import_dates => 'Statement dates';

  @override
  String get import_decimal_comma => 'A comma (1,50)';

  @override
  String get import_decimal_dot => 'A dot (1.50)';

  @override
  String get import_decimalSeparator => 'Decimal separator';

  @override
  String get import_delimiter => 'Columns are separated by';

  @override
  String get import_delimiter_comma => 'Commas';

  @override
  String get import_delimiter_pipe => 'Vertical bars';

  @override
  String get import_delimiter_semicolon => 'Semicolons';

  @override
  String get import_delimiter_tab => 'Tabs';

  @override
  String get import_directionIn => 'Text that means money in';

  @override
  String get import_directionOut => 'Text that means money out';

  @override
  String import_done(String count, String account) {
    return 'Imported $count transactions into $account.';
  }

  @override
  String get import_doneDetail => 'They\'re in Needs a category, ready for you to sort.';

  @override
  String get import_dropHint => 'Or drop the file onto this box.';

  @override
  String get import_duplicatesIntro =>
      'Already in your ledger from an earlier import, so they\'re skipped.';

  @override
  String get import_excludeAll => 'Leave all out';

  @override
  String get import_file => 'Statement file';

  @override
  String get import_fileHint =>
      'OFX, QFX, QIF or CSV, up to 5 MB. It is read by your own Budget Owl server and isn\'t kept after the import.';

  @override
  String get import_format => 'Format';

  @override
  String get import_format_CSV => 'CSV (a spreadsheet export)';

  @override
  String get import_format_OFX => 'OFX or QFX';

  @override
  String get import_format_QIF => 'QIF';

  @override
  String get import_formatHint =>
      'Picked from the file\'s name. Change it if that\'s wrong; the file is checked either way.';

  @override
  String get import_goToAccounts => 'Go to accounts';

  @override
  String get import_grouping_apostrophe => 'An apostrophe (1\'000)';

  @override
  String get import_grouping_comma => 'A comma (1,000)';

  @override
  String get import_grouping_dot => 'A dot (1.000)';

  @override
  String get import_grouping_narrow => 'A narrow space';

  @override
  String get import_grouping_nbsp => 'A non-breaking space';

  @override
  String get import_grouping_none => 'None';

  @override
  String get import_grouping_space => 'A space (1 000)';

  @override
  String get import_groupingSeparator => 'Thousands separator';

  @override
  String get import_headerRow => 'Header row';

  @override
  String get import_headerRowHint => 'Which line holds the column names. 0 if the file has none.';

  @override
  String get import_history => 'Recent imports';

  @override
  String import_historyCounts(
    String imported,
    String duplicate,
    String excluded,
    String unreadable,
  ) {
    return '$imported added · $duplicate already there · $excluded left out · $unreadable unreadable';
  }

  @override
  String get import_historyEmpty => 'Nothing imported yet.';

  @override
  String get import_historyNothingLeft => 'Nothing left to undo';

  @override
  String import_historyRow(String account, String format, String date) {
    return '$account · $format · $date';
  }

  @override
  String get import_historyUndo => 'Undo';

  @override
  String get import_identical => 'Identical rows';

  @override
  String import_identicalCount(String count) {
    return '× $count';
  }

  @override
  String get import_identicalIntro =>
      'These rows are exactly alike, which is normal (two coffees on one day). Each one is imported.';

  @override
  String get import_include => 'Include';

  @override
  String get import_includeAll => 'Include all';

  @override
  String get import_lede =>
      'Bring in transactions from a file your bank exported. You\'ll see exactly what will be added before anything is.';

  @override
  String import_line(String row) {
    return 'Line $row';
  }

  @override
  String import_lineColumn(String row, String column) {
    return 'Line $row, column $column';
  }

  @override
  String get import_mappingChanged =>
      'This file\'s columns aren\'t the same as the last file you imported into this account. Check the settings before you update the preview.';

  @override
  String get import_mappingSaved => 'These are the settings you saved for this account.';

  @override
  String get import_mappingSuggested =>
      'Budget Owl guessed these settings from the file. Check them, then update the preview.';

  @override
  String get import_moneyIn => 'Money in';

  @override
  String get import_moneyOut_columns => 'A separate column';

  @override
  String get import_moneyOut_negative => 'A negative amount';

  @override
  String get import_moneyOut_positive => 'A positive amount (flip the signs)';

  @override
  String get import_moneyOutAs => 'Money out appears as';

  @override
  String get import_moneyOutColumnsHint =>
      'Mark the columns below as Money out and Money in, or as In or out.';

  @override
  String get import_moneyOutTotal => 'Money out';

  @override
  String get import_noAccountsBody =>
      'An import needs an account to put the transactions in. Add one first.';

  @override
  String get import_nothingNew => 'There\'s nothing new to add from this file.';

  @override
  String get import_nothingWritten => 'Nothing is added until you confirm.';

  @override
  String get import_outOfDate =>
      'You have changed how the file is read. Update the preview to see what that does before you import.';

  @override
  String get import_preview => 'Preview the import';

  @override
  String get import_readFailed => 'Your browser couldn\'t open that file. Try choosing it again.';

  @override
  String get import_ready => 'Preview ready.';

  @override
  String import_resembles(String date) {
    return 'Looks like the one already in your ledger on $date.';
  }

  @override
  String get import_reusedIdentifier =>
      'Your bank\'s file used the same reference for more than one transaction, so Budget Owl told them apart by date, amount and payee instead.';

  @override
  String get import_role_AMOUNT => 'Amount';

  @override
  String get import_role_CREDIT => 'Money in';

  @override
  String get import_role_CURRENCY => 'Currency';

  @override
  String get import_role_DATE => 'Date';

  @override
  String get import_role_DEBIT => 'Money out';

  @override
  String get import_role_DIRECTION => 'In or out';

  @override
  String get import_role_NOTE => 'Note';

  @override
  String get import_role_PAYEE => 'Payee';

  @override
  String get import_role_REFERENCE => 'Reference';

  @override
  String get import_role_none => 'Not used';

  @override
  String get import_row_row_amount_too_large => 'The amount is larger than Budget Owl records.';

  @override
  String get import_row_row_amount_too_precise => 'The amount has more than four decimal places.';

  @override
  String get import_row_row_amount_unreadable => 'The amount can\'t be read.';

  @override
  String get import_row_row_columns_short => 'It has fewer columns than the rows around it.';

  @override
  String get import_row_row_currency_mismatch => 'It\'s in a different currency from the account.';

  @override
  String get import_row_row_date_ambiguous => 'The date can be read more than one way.';

  @override
  String get import_row_row_date_out_of_range => 'The date is too far in the past or the future.';

  @override
  String get import_row_row_date_unreadable => 'The date can\'t be read.';

  @override
  String get import_row_row_entity_unknown =>
      'It contains a character code Budget Owl doesn\'t recognise.';

  @override
  String get import_row_row_missing_field => 'It\'s missing a date or an amount.';

  @override
  String get import_row_unknown => 'It can\'t be read.';

  @override
  String get import_sample => 'The first rows to be added';

  @override
  String get import_saveMapping => 'Remember these settings for this account';

  @override
  String get import_saveMappingHint =>
      'Saved when you import, so the next file from this bank needs no setting up.';

  @override
  String get import_stale =>
      'Your ledger changed since this preview, so nothing was imported. This is the preview again, with what\'s there now.';

  @override
  String import_staleDuplicates(String count) {
    return '$count of these were added by somebody else a moment ago.';
  }

  @override
  String get import_startOver => 'Start over';

  @override
  String get import_stepFile => 'The file, and where it goes';

  @override
  String get import_stepMapping => 'How to read it';

  @override
  String get import_stepPreview => 'What will be added';

  @override
  String get import_summary => 'Summary';

  @override
  String get import_suspectedIntro =>
      'These look like transactions somebody already typed in, so they\'re left out unless you tick them.';

  @override
  String get import_textCut =>
      'Some payees or notes were longer than Budget Owl keeps, so they\'ve been shortened.';

  @override
  String get import_title => 'Import a statement';

  @override
  String get import_tooLarge =>
      'That file is larger than 5 MB, the most Budget Owl reads at once. Export a shorter date range from your bank and import it in parts.';

  @override
  String get import_undo => 'Undo this import';

  @override
  String get import_undoHeading => 'Undo this import?';

  @override
  String import_undoMessage(String count, String account) {
    return 'This removes the $count transactions this import added to $account. Any of them since linked to a bill, a debt payment or an envelope lose that link. Rows you have paired into a transfer stay.';
  }

  @override
  String import_undone(String count) {
    return 'Import undone. $count transactions removed.';
  }

  @override
  String get import_unreadableIntro =>
      'These lines couldn\'t be read and won\'t be imported. Fix them in the file and import it again if you need them.';

  @override
  String get import_updatePreview => 'Update preview';

  @override
  String get import_viewLedger => 'See them in the ledger';

  @override
  String get import_viewerBody =>
      'Your role in this household is read-only. An owner or a member can import a statement for you.';

  @override
  String get import_viewerHeading => 'Viewers can\'t import statements';

  @override
  String get import_working => 'Reading the file…';

  @override
  String insights_change_down(String percent) {
    return 'down $percent';
  }

  @override
  String get insights_change_downSmall => 'down less than 1%';

  @override
  String get insights_change_flat => 'flat';

  @override
  String get insights_change_new => 'new';

  @override
  String insights_change_up(String percent) {
    return 'up $percent';
  }

  @override
  String get insights_change_upSmall => 'up less than 1%';

  @override
  String get insights_chart_label => 'Income and spending by month';

  @override
  String get insights_chart_negative =>
      'A month where refunds were more than spending is drawn at zero. The table shows its real figure.';

  @override
  String get insights_coincident =>
      'Income and spending were the same every month, so they are drawn as one line.';

  @override
  String get insights_currency_label => 'Currency';

  @override
  String get insights_currency_note =>
      'Each currency is reported on its own, with no combined figure: adding them together would need an exchange rate.';

  @override
  String get insights_download => 'Download CSV';

  @override
  String get insights_downloadIncome => 'of income and spending by month';

  @override
  String insights_downloadWhere(String month) {
    return 'of where $month went';
  }

  @override
  String get insights_downloading => 'Preparing the file…';

  @override
  String insights_empty(String from, String to) {
    return 'Nothing recorded between $from and $to yet.';
  }

  @override
  String get insights_empty_link => 'Record a transaction';

  @override
  String get insights_envelopes =>
      'Insights counts the transactions in your ledger. Amounts logged straight into an envelope don\'t appear here.';

  @override
  String insights_figures_caption(String range, String currency) {
    return 'Income and spending by month, $range, in $currency.';
  }

  @override
  String get insights_figures_income => 'Income';

  @override
  String get insights_figures_kept => 'Kept';

  @override
  String get insights_figures_month => 'Month';

  @override
  String get insights_figures_show => 'Show the figures';

  @override
  String get insights_figures_spending => 'Spending';

  @override
  String get insights_figures_uncategorisedIn => 'Needs a category, in';

  @override
  String get insights_figures_uncategorisedOut => 'Needs a category, out';

  @override
  String get insights_income_heading => 'Income vs spending';

  @override
  String insights_keptLong(String amount, String month, String best) {
    return 'Kept an average of $amount a month. Best month was $month, at $best.';
  }

  @override
  String insights_keptLongPartial(String amount, String count, String month, String best) {
    return 'Kept an average of $amount a month over the $count complete months. Best month was $month, at $best.';
  }

  @override
  String insights_keptLongPartialOne(String amount, String month) {
    return 'Kept $amount in the one complete month so far, $month.';
  }

  @override
  String insights_keptShort(String amount) {
    return 'Avg kept $amount/mo';
  }

  @override
  String get insights_lede =>
      'What actually happened in your ledger, month by month. Every figure is added up from your transactions each time you open this page.';

  @override
  String get insights_legend_income => 'Income';

  @override
  String get insights_legend_spending => 'Spending';

  @override
  String get insights_range_12 => '12 mo';

  @override
  String get insights_range_12_name => 'Last 12 months';

  @override
  String get insights_range_3 => '3 mo';

  @override
  String get insights_range_3_name => 'Last 3 months';

  @override
  String get insights_range_6 => '6 mo';

  @override
  String get insights_range_6_name => 'Last 6 months';

  @override
  String get insights_range_label => 'Range';

  @override
  String insights_said_income(String first, String firstMonth, String last, String lastMonth) {
    return 'Income: $first in $firstMonth, to $last in $lastMonth.';
  }

  @override
  String insights_said_spending(String first, String firstMonth, String last, String lastMonth) {
    return 'Spending: $first in $firstMonth, to $last in $lastMonth.';
  }

  @override
  String insights_soFar(String month) {
    return '$month (so far)';
  }

  @override
  String insights_status_month(String month) {
    return 'Showing where $month went.';
  }

  @override
  String insights_status_range(String range) {
    return 'Showing $range.';
  }

  @override
  String get insights_title => 'Insights';

  @override
  String get insights_uncategorised_link => 'Give them a category';

  @override
  String insights_uncategorised_many_both(String count, String out, String inValue) {
    return '$count transactions need a category, so $out out and $inValue in aren\'t counted as income or spending.';
  }

  @override
  String insights_uncategorised_many_single(String count, String amount) {
    return '$count transactions need a category, so $amount isn\'t counted as income or spending.';
  }

  @override
  String insights_uncategorised_one_both(String out, String inValue) {
    return '1 transaction needs a category, so $out out and $inValue in aren\'t counted as income or spending.';
  }

  @override
  String insights_uncategorised_one_single(String amount) {
    return '1 transaction needs a category, so $amount isn\'t counted as income or spending.';
  }

  @override
  String get insights_where_archived => 'Archived';

  @override
  String insights_where_heading(String month) {
    return 'Where $month went';
  }

  @override
  String insights_where_headingSoFar(String month) {
    return 'Where $month has gone so far';
  }

  @override
  String insights_where_hideChildren(String name) {
    return 'Hide what\'s in $name';
  }

  @override
  String insights_where_list(String month) {
    return 'Spending by category in $month';
  }

  @override
  String get insights_where_navigation => 'Choose a month';

  @override
  String get insights_where_next => 'Next month';

  @override
  String insights_where_nothing(String month) {
    return 'Nothing was spent in $month.';
  }

  @override
  String get insights_where_previous => 'Previous month';

  @override
  String insights_where_showAll(String count) {
    return 'Show all $count categories';
  }

  @override
  String insights_where_showChildren(String name) {
    return 'Show what\'s in $name';
  }

  @override
  String get insights_where_showFewer => 'Show fewer categories';

  @override
  String insights_where_total(String amount, String compared, String comparedMonth) {
    return '$amount spent, against $compared in $comparedMonth.';
  }

  @override
  String insights_where_uncategorised_many(String amount, String count) {
    return 'Needs a category: $amount across $count transactions, not in the list above.';
  }

  @override
  String insights_where_uncategorised_one(String amount) {
    return 'Needs a category: $amount across 1 transaction, not in the list above.';
  }

  @override
  String get invite_another => 'Invite another person';

  @override
  String get invite_copied => 'Link copied.';

  @override
  String get invite_copy => 'Copy link';

  @override
  String get invite_copyManual => 'The link is selected. Copy it with your keyboard.';

  @override
  String get invite_create => 'Create link';

  @override
  String get invite_creating => 'Creating link…';

  @override
  String invite_credentialWarning(String role, String expires) {
    return 'Treat this link like a password: anyone who has it can join this household as $role. It works once and expires $expires.';
  }

  @override
  String get invite_email => 'Their email address';

  @override
  String get invite_emailHint =>
      'If they are new to this instance, this becomes the address they sign in with — so check it. The link is what lets them in; the address is who they become.';

  @override
  String get invite_intro =>
      'Create a link and send it to them however you like — a message, or read it out. There is no email.';

  @override
  String get invite_linkLabel => 'Invitation link';

  @override
  String get invite_linkUnavailable =>
      'The link could not be built. Cancel this invitation and create a new one.';

  @override
  String get invite_revoke => 'Revoke link';

  @override
  String get invite_revoked => 'This link has been revoked and can no longer be used.';

  @override
  String get invite_role => 'Their role';

  @override
  String invite_shareIntro(String email) {
    return 'Share this link with $email. This is the only time it is shown.';
  }

  @override
  String get invite_title => 'Invite someone';

  @override
  String get join_accept => 'Accept and join';

  @override
  String get join_accountNote =>
      'New here? Enter your name and choose a password. Already have an account on this instance? Leave both fields empty and you will be added with it.';

  @override
  String get join_disclosure_heading => 'Before you join: who can see your finances';

  @override
  String get join_disclosure_nothingHidden =>
      'Budget Owl does not hide your data from them, and everyone else in the household can see it too.';

  @override
  String get join_disclosure_operator =>
      'The person who runs this instance can see everything you record in it: every transaction, every balance, every note.';

  @override
  String get join_disclosure_trust =>
      'Only join if you are comfortable sharing your finances with the person who runs this server.';

  @override
  String get join_existingAccount => 'I already have an account on this server';

  @override
  String get join_formHeading => 'Accept the invitation';

  @override
  String join_joinedAccountCreated(String email) {
    return 'Your account has been created. Sign in with $email.';
  }

  @override
  String join_joinedHeading(String household) {
    return 'Welcome to $household';
  }

  @override
  String join_joinedRole(String role) {
    return 'You joined as $role.';
  }

  @override
  String get join_joinedTitle => 'You have joined';

  @override
  String get join_joining => 'Joining…';

  @override
  String get join_lede =>
      'Someone has invited you to share a household\'s budget on this Budget Owl instance.';

  @override
  String get join_name => 'Your name';

  @override
  String get join_password => 'Choose a password';

  @override
  String get join_pasteHeading => 'Paste your invitation link';

  @override
  String get join_pasteHint =>
      'The link you were sent. It starts with this server\'s address and has /join/ in it.';

  @override
  String get join_pasteLabel => 'Invitation link';

  @override
  String get join_problem_notALink =>
      'That isn\'t an invitation link. It should be this server\'s address followed by /join/ and a long code.';

  @override
  String join_problem_otherHost(String host) {
    return 'That invitation is for $host, not the server you entered. Change the server first if you meant that one.';
  }

  @override
  String get join_signInNow => 'Sign in';

  @override
  String get join_signedInBody =>
      'An invitation can only be accepted by someone who is signed out. Sign out here, and this page will let you accept it.';

  @override
  String get join_signedInHeading => 'You are already signed in';

  @override
  String get join_title => 'Join a household';

  @override
  String get join_toSignIn => 'Go to sign in';

  @override
  String get join_unusableTitle => 'This invitation cannot be used';

  @override
  String get login_deviceLabel => 'Name for this phone';

  @override
  String get login_deviceLabelHint =>
      'Shown on your devices list, so you can tell which phone to sign out.';

  @override
  String get login_email => 'Email address';

  @override
  String get login_heading => 'Your account';

  @override
  String get login_lede => 'Welcome back. Sign in to see your household\'s budget.';

  @override
  String get login_notice_created => 'Your account is ready. Sign in to continue.';

  @override
  String get login_notice_expired => 'Your session ended. Sign in again to continue.';

  @override
  String get login_notice_joined => 'You have joined the household. Sign in to continue.';

  @override
  String get login_password => 'Password';

  @override
  String get login_passwordLoginDisabled =>
      'This server has turned off signing in with an email and password, and the app can only sign in that way for now. Sign in on the web instead.';

  @override
  String login_server(String host) {
    return 'Signing in to $host';
  }

  @override
  String get login_signIn => 'Sign in';

  @override
  String get login_signingIn => 'Signing in…';

  @override
  String get login_title => 'Sign in';

  @override
  String get members_heading => 'Members';

  @override
  String get members_invite => 'Invite someone';

  @override
  String members_joined(String date) {
    return 'Joined $date';
  }

  @override
  String get members_leave => 'Leave';

  @override
  String get members_leaveHeading => 'Leave this household?';

  @override
  String get members_leaveMessage =>
      'You will lose access straight away. What you recorded stays in the household, and you will need a new invitation to come back.';

  @override
  String get members_remove => 'Remove';

  @override
  String members_removeHeading(String name) {
    return 'Remove $name?';
  }

  @override
  String members_removeMessage(String name) {
    return '$name will lose access to this household straight away and be signed out everywhere. What they recorded stays in the household.';
  }

  @override
  String members_roleFor(String name) {
    return 'Role for $name';
  }

  @override
  String get members_soleOwner =>
      'You are the only owner. Make someone else an owner before you can leave.';

  @override
  String get more_changeServer => 'Change server';

  @override
  String get more_changeServerHeading => 'Change server?';

  @override
  String more_changeServerMessage(String host) {
    return 'You\'ll be signed out of $host, and everything Budget Owl keeps on this phone is erased. Your household\'s data stays on the server.';
  }

  @override
  String get more_server => 'Server';

  @override
  String get more_signOutHeading => 'Sign out?';

  @override
  String get more_signOutMessage =>
      'Everything Budget Owl keeps on this phone is erased. Your household\'s data stays on the server.';

  @override
  String get more_title => 'More';

  @override
  String get nav_accounts => 'Accounts';

  @override
  String get nav_admin => 'Admin';

  @override
  String get nav_bills => 'Bills';

  @override
  String get nav_categories => 'Categories';

  @override
  String get nav_checkIn => 'Check-in';

  @override
  String get nav_debtPlan => 'Debt plan';

  @override
  String get nav_devices => 'Devices';

  @override
  String get nav_envelopes => 'Envelopes';

  @override
  String get nav_goals => 'Goals';

  @override
  String get nav_household => 'Household';

  @override
  String get nav_import => 'Import';

  @override
  String get nav_insights => 'Insights';

  @override
  String get nav_label => 'Main';

  @override
  String get nav_more => 'More';

  @override
  String get nav_preferences => 'Preferences';

  @override
  String get nav_signOut => 'Sign out';

  @override
  String get nav_transactions => 'Transactions';

  @override
  String get noHousehold_body =>
      'You are signed in, but nobody has added you to a household. Ask the person who runs this instance for an invitation link, then open it in this browser.';

  @override
  String get noHousehold_bodyPhone =>
      'You are signed in, but nobody has added you to a household. Ask the person who runs this server for an invitation link, then paste it on the connect screen.';

  @override
  String get noHousehold_title => 'You are not part of a household yet';

  @override
  String get offline_dayAgo => '1 day ago';

  @override
  String offline_daysAgo(String count) {
    return '$count days ago';
  }

  @override
  String get offline_hourAgo => '1 hour ago';

  @override
  String offline_hoursAgo(String count) {
    return '$count hours ago';
  }

  @override
  String get offline_justNow => 'just now';

  @override
  String get offline_minuteAgo => '1 minute ago';

  @override
  String offline_minutesAgo(String count) {
    return '$count minutes ago';
  }

  @override
  String get offline_noCopy => 'You\'re offline, and this phone has no saved copy of this yet.';

  @override
  String offline_staleNoAnswer(String ago) {
    return 'Last updated $ago — the server didn\'t answer';
  }

  @override
  String offline_staleOffline(String ago) {
    return 'Last updated $ago — you\'re offline';
  }

  @override
  String get offline_versionUnconfirmed =>
      'Changes are off until the server confirms it runs a version this app works with. Refresh to check again.';

  @override
  String get offline_write => 'You\'re offline — this needs a connection';

  @override
  String get pairing_account => 'Other account';

  @override
  String get pairing_allAccounts => 'All other accounts';

  @override
  String pairing_candidate(String date, String account, String amount) {
    return '$date · $account · $amount';
  }

  @override
  String get pairing_candidatesLabel => 'Rows to choose from';

  @override
  String get pairing_chooseFirst => 'Choose the other row first.';

  @override
  String get pairing_confirm => 'Pair these two rows';

  @override
  String get pairing_intro =>
      'Choose the row in your other account that this money moved to or from. Nothing is chosen for you.';

  @override
  String get pairing_loading => 'Finding rows…';

  @override
  String get pairing_loss =>
      'Both rows will lose their category and payee, and stop counting as income or spending. Splitting them later brings back two rows with no category.';

  @override
  String get pairing_none => 'No rows match. Try another account or other dates.';

  @override
  String get pairing_paired => 'Paired into a transfer.';

  @override
  String get pairing_split => 'Split';

  @override
  String get pairing_splitDone => 'Split into two rows. Both need a category.';

  @override
  String get pairing_splitHeading => 'Split this transfer into two rows?';

  @override
  String get pairing_splitMessage =>
      'Both rows stay, with their amounts and dates, but come back with no category and no payee, so they\'ll be in Needs a category. They\'ll count as income and spending again.';

  @override
  String get pairing_thisRow => 'This row';

  @override
  String get pairing_title => 'Pair this with the other half of a transfer';

  @override
  String get placeholder_body =>
      'This screen is coming to the app in the next update. It works on the web today.';

  @override
  String get preferences_currency => 'Show amounts in';

  @override
  String get preferences_currencyHint =>
      'Each account keeps its own currency; this is the one totals are shown in for you.';

  @override
  String get preferences_followDevice => 'Follow this device';

  @override
  String preferences_followHousehold(String currency) {
    return 'Follow the household ($currency)';
  }

  @override
  String get preferences_heading => 'Display';

  @override
  String get preferences_lede =>
      'These are yours alone. They change what you see, never what is recorded, so everyone in the household can read the same figures their own way.';

  @override
  String get preferences_locale => 'Language and number format';

  @override
  String get preferences_localeHint =>
      'Text appears in this language where a translation exists, and in English otherwise. Dates and numbers always follow your choice.';

  @override
  String get preferences_saved => 'Preferences saved.';

  @override
  String get preferences_title => 'Your preferences';

  @override
  String get privacy_hidden => 'Budget Owl is hidden while the app is in the background.';

  @override
  String get role_MEMBER => 'Member';

  @override
  String get role_MEMBER_description => 'can see and change the household\'s money';

  @override
  String get role_OWNER => 'Owner';

  @override
  String get role_OWNER_description => 'everything, including inviting and removing people';

  @override
  String get role_VIEWER => 'Viewer';

  @override
  String get role_VIEWER_description => 'can see the household\'s money, but not change it';

  @override
  String get setup_accountHeading => 'Your account';

  @override
  String get setup_baseCurrency => 'Base currency';

  @override
  String get setup_baseCurrencyHint =>
      'The currency totals are shown in by default. Each member can choose to see another.';

  @override
  String get setup_create => 'Create account';

  @override
  String get setup_creating => 'Creating account…';

  @override
  String get setup_email => 'Email address';

  @override
  String get setup_householdName => 'Household name';

  @override
  String get setup_lede =>
      'This instance is new. Create the first account — it becomes the owner of your household.';

  @override
  String get setup_name => 'Your name';

  @override
  String get setup_operatorNote =>
      'You are the person running this instance, so you will be able to see everything anyone in your household records. Anyone you invite is told that before they join.';

  @override
  String get setup_password => 'Password';

  @override
  String setup_passwordHint(String min) {
    return 'At least $min characters. A long phrase is stronger than a clever short one.';
  }

  @override
  String get setup_title => 'Set up Budget Owl';

  @override
  String get theme_toDark => 'Switch to dark theme';

  @override
  String get theme_toLight => 'Switch to light theme';

  @override
  String get title_accounts => 'Accounts';

  @override
  String get title_admin => 'Admin';

  @override
  String get title_bills => 'Bills & income';

  @override
  String get title_categories => 'Categories';

  @override
  String get title_checkIn => 'Weekly check-in';

  @override
  String get title_debtDetail => 'Debt';

  @override
  String get title_debtPlan => 'Debt plan';

  @override
  String get title_devices => 'Devices';

  @override
  String get title_envelopes => 'Envelopes';

  @override
  String get title_goalDetail => 'Goal';

  @override
  String get title_goals => 'Goals';

  @override
  String get title_household => 'Household';

  @override
  String get title_import => 'Import a statement';

  @override
  String get title_insights => 'Insights';

  @override
  String get title_join => 'Join a household';

  @override
  String get title_login => 'Sign in';

  @override
  String get title_preferences => 'Preferences';

  @override
  String get title_setup => 'Set up';

  @override
  String get title_transactions => 'Transactions';

  @override
  String get transactions_account => 'Account';

  @override
  String get transactions_accountFixedHint =>
      'A transaction stays in the account it was recorded against. To move it, delete it and record it again.';

  @override
  String transactions_actionsFor(String name) {
    return 'Actions for $name';
  }

  @override
  String get transactions_add => 'Record a transaction';

  @override
  String get transactions_addFirst => 'Record your first transaction';

  @override
  String get transactions_addTitle => 'Record a transaction';

  @override
  String get transactions_allAccounts => 'All accounts';

  @override
  String get transactions_allCategories => 'All categories';

  @override
  String get transactions_amount => 'Amount';

  @override
  String transactions_amountHint(String currency) {
    return 'In $currency, the currency of the account you chose.';
  }

  @override
  String transactions_balanceOf(String account) {
    return '$account balance';
  }

  @override
  String get transactions_betweenAccounts => 'Between your accounts';

  @override
  String get transactions_categorise => 'Add a category';

  @override
  String get transactions_category => 'Category';

  @override
  String transactions_categoryPath(String parent, String name) {
    return '$parent → $name';
  }

  @override
  String transactions_categoryTrail(String parent, String name) {
    return '$parent › $name';
  }

  @override
  String get transactions_chooseCategory => 'Choose a category';

  @override
  String get transactions_clearFilters => 'Clear filters';

  @override
  String get transactions_created => 'Transaction recorded.';

  @override
  String get transactions_date => 'Date';

  @override
  String get transactions_deleteHeading => 'Delete this transaction?';

  @override
  String get transactions_deleteMessage =>
      'Your account balance goes back to what it was. This can\'t be undone.';

  @override
  String get transactions_deleted => 'Transaction deleted.';

  @override
  String get transactions_direction => 'Which way did the money go?';

  @override
  String get transactions_directionIn => 'Money in';

  @override
  String get transactions_directionOut => 'Money out';

  @override
  String get transactions_editTitle => 'Edit transaction';

  @override
  String get transactions_emptyBody =>
      'Nothing recorded yet. Add what you\'ve spent and your account balances will start to mean something.';

  @override
  String get transactions_emptyReadOnly =>
      'Ask an owner or member of your household to record some.';

  @override
  String get transactions_filterButton => 'Filter';

  @override
  String get transactions_filtersLabel => 'Narrow the list';

  @override
  String get transactions_from => 'From';

  @override
  String get transactions_goToAccounts => 'Go to accounts';

  @override
  String get transactions_heading => 'Your transactions';

  @override
  String get transactions_importStatement => 'Import a statement';

  @override
  String get transactions_lede =>
      'Everything that\'s happened to your money. Record what you spend as you go — you can always put a category on it later.';

  @override
  String get transactions_mixedCurrencies => 'No total — this page mixes currencies.';

  @override
  String get transactions_needsCategory => 'Needs a category';

  @override
  String get transactions_needsCategoryFilter => 'Needs a category';

  @override
  String get transactions_noAccountsBody =>
      'A transaction has to go somewhere, so add an account first.';

  @override
  String get transactions_noMatches =>
      'Nothing matches what you\'re looking for. Try widening the dates, or clearing the filters.';

  @override
  String get transactions_noPayee => 'No payee';

  @override
  String get transactions_note => 'Note';

  @override
  String get transactions_noteHint => 'Optional.';

  @override
  String transactions_pageOf(String page, String pages) {
    return 'Page $page of $pages';
  }

  @override
  String get transactions_pageTotal => 'This page adds up to';

  @override
  String get transactions_pagerLabel => 'Pages of transactions';

  @override
  String get transactions_pair => 'This is a transfer…';

  @override
  String get transactions_payee => 'Who it was with';

  @override
  String get transactions_payeeHint =>
      'Optional. Start typing and we\'ll suggest ones you\'ve used before.';

  @override
  String get transactions_readOnlyRow =>
      'Your role in this household can read transactions but not change them.';

  @override
  String get transactions_search => 'Search';

  @override
  String get transactions_searchHint => 'Looks in notes and payee names.';

  @override
  String get transactions_searchPlaceholder => 'Search payees and notes';

  @override
  String transactions_showing(String shown, String total) {
    return 'Showing $shown of $total';
  }

  @override
  String get transactions_split => 'Split into two rows';

  @override
  String get transactions_status_CLEARED => 'Cleared';

  @override
  String get transactions_status_PENDING => 'Pending';

  @override
  String get transactions_status_RECONCILED => 'Reconciled';

  @override
  String get transactions_status_unknown => 'Other';

  @override
  String get transactions_statusHint =>
      'Leave this as Cleared unless you\'re recording something you expect to settle later.';

  @override
  String get transactions_statusLabel => 'Status';

  @override
  String get transactions_title => 'Transactions';

  @override
  String get transactions_to => 'To';

  @override
  String get transactions_transfer => 'Transfer';

  @override
  String get transactions_transferIn => 'Transfer in';

  @override
  String get transactions_transferOut => 'Transfer out';

  @override
  String get transactions_transferPairOnly =>
      'Both halves of a transfer change together, so this row can\'t be edited on its own.';

  @override
  String get transactions_updated => 'Transaction saved.';

  @override
  String transfers_accountsFixed(String from, String to) {
    return '$from → $to';
  }

  @override
  String get transfers_accountsFixedHint =>
      'A transfer stays between the two accounts you made it with, so there\'s nothing to change here. To move it to different accounts, delete it and make it again.';

  @override
  String get transfers_accountsLabel => 'Accounts';

  @override
  String transfers_accountsTrail(String from, String to) {
    return '$from › $to';
  }

  @override
  String get transfers_addTitle => 'Move money between your accounts';

  @override
  String get transfers_amount => 'Amount that left';

  @override
  String transfers_amountHint(String currency) {
    return 'In $currency, the currency of the account it leaves. No minus sign — which way it went is the two accounts above.';
  }

  @override
  String get transfers_amountHintNoAccount =>
      'Choose the account it leaves and we\'ll tell you which currency this is in.';

  @override
  String get transfers_amountReceived => 'Amount that arrived';

  @override
  String transfers_amountReceivedHint(String received) {
    return 'These two accounts are in different currencies, so what arrived isn\'t the same number as what left — and only your statement knows it. Copy it from there, in $received.';
  }

  @override
  String get transfers_chooseAccount => 'Choose an account';

  @override
  String get transfers_created => 'Money moved.';

  @override
  String get transfers_date => 'Date';

  @override
  String get transfers_deleteHeading => 'Delete this transfer?';

  @override
  String get transfers_deleteMessage =>
      'Both halves go, and both account balances go back to what they were. This can\'t be undone.';

  @override
  String get transfers_deleted => 'Transfer deleted.';

  @override
  String get transfers_editBothHalves =>
      'Changes here apply to both halves — what left one account and what arrived in the other stay in step.';

  @override
  String get transfers_editTitle => 'Edit this transfer';

  @override
  String get transfers_from => 'Out of';

  @override
  String transfers_impliedRate(String sent, String rate, String received) {
    return 'That works out at 1 $sent = $rate $received.';
  }

  @override
  String get transfers_impliedRateNote =>
      'Worked out by dividing the two figures above, just so you can see it. It isn\'t the rate your bank gave you — that had its fee inside it — and nothing in Budget Owl uses it.';

  @override
  String transfers_legDates(String from, String to) {
    return 'The two halves are dated $from and $to. A date saved here moves both to it; leave it as it is and each keeps its own.';
  }

  @override
  String get transfers_legNotes =>
      'The two halves have different notes. A note saved here replaces both; leave it as it is and each keeps its own.';

  @override
  String get transfers_move => 'Move money';

  @override
  String get transfers_noCategoryNote =>
      'Moving money between two of your own accounts isn\'t spending and isn\'t income, so a transfer has no category and nobody to pay.';

  @override
  String get transfers_note => 'Note';

  @override
  String get transfers_noteHint => 'Optional. Both halves of the transfer get it.';

  @override
  String get transfers_to => 'Into';

  @override
  String get transfers_updated => 'Transfer saved.';

  @override
  String get validation_amount => 'Enter an amount using digits and a dot, like 1250.00 or -400.';

  @override
  String get validation_currency => 'Enter a three-letter currency code.';

  @override
  String get validation_email => 'Enter a valid email address.';

  @override
  String get validation_issuerUrl =>
      'Enter the provider\'s address starting with https:// — or http:// only if it runs on this same machine.';

  @override
  String get validation_lastFour => 'Enter exactly four digits, or leave this empty.';

  @override
  String validation_maxBytes(String max) {
    return 'That is too long. Use at most $max bytes.';
  }

  @override
  String validation_maxLength(String max) {
    return 'Use at most $max characters.';
  }

  @override
  String validation_minLength(String min) {
    return 'Use at least $min characters.';
  }

  @override
  String get validation_positiveAmount =>
      'Enter an amount using digits and a dot, like 200 or 49.99. No minus sign.';

  @override
  String get validation_required => 'This field is required.';

  @override
  String get validation_sameAccount =>
      'Choose a different account. Money can\'t move from an account to itself.';

  @override
  String get validation_server => 'This value was not accepted. Check it and try again.';
}
