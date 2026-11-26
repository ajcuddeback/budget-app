import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:intl/intl.dart' as intl;

import 'app_localizations_en.dart';

// ignore_for_file: type=lint

/// Callers can lookup localized strings with an instance of AppLocalizations
/// returned by `AppLocalizations.of(context)`.
///
/// Applications need to include `AppLocalizations.delegate()` in their app's
/// `localizationDelegates` list, and the locales they support in the app's
/// `supportedLocales` list. For example:
///
/// ```dart
/// import 'gen/app_localizations.dart';
///
/// return MaterialApp(
///   localizationsDelegates: AppLocalizations.localizationsDelegates,
///   supportedLocales: AppLocalizations.supportedLocales,
///   home: MyApplicationHome(),
/// );
/// ```
///
/// ## Update pubspec.yaml
///
/// Please make sure to update your pubspec.yaml to include the following
/// packages:
///
/// ```yaml
/// dependencies:
///   # Internationalization support.
///   flutter_localizations:
///     sdk: flutter
///   intl: any # Use the pinned version from flutter_localizations
///
///   # Rest of dependencies
/// ```
///
/// ## iOS Applications
///
/// iOS applications define key application metadata, including supported
/// locales, in an Info.plist file that is built into the application bundle.
/// To configure the locales supported by your app, you’ll need to edit this
/// file.
///
/// First, open your project’s ios/Runner.xcworkspace Xcode workspace file.
/// Then, in the Project Navigator, open the Info.plist file under the Runner
/// project’s Runner folder.
///
/// Next, select the Information Property List item, select Add Item from the
/// Editor menu, then select Localizations from the pop-up menu.
///
/// Select and expand the newly-created Localizations item then, for each
/// locale your application supports, add a new item and select the locale
/// you wish to add from the pop-up menu in the Value field. This list should
/// be consistent with the languages listed in the AppLocalizations.supportedLocales
/// property.
abstract class AppLocalizations {
  AppLocalizations(String locale) : localeName = intl.Intl.canonicalizedLocale(locale.toString());

  final String localeName;

  static AppLocalizations of(BuildContext context) {
    return Localizations.of<AppLocalizations>(context, AppLocalizations)!;
  }

  static const LocalizationsDelegate<AppLocalizations> delegate = _AppLocalizationsDelegate();

  /// A list of this localizations delegate along with the default localizations
  /// delegates.
  ///
  /// Returns a list of localizations delegates containing this delegate along with
  /// GlobalMaterialLocalizations.delegate, GlobalCupertinoLocalizations.delegate,
  /// and GlobalWidgetsLocalizations.delegate.
  ///
  /// Additional delegates can be added by appending to this list in
  /// MaterialApp. This list does not have to be used at all if a custom list
  /// of delegates is preferred or required.
  static const List<LocalizationsDelegate<dynamic>> localizationsDelegates =
      <LocalizationsDelegate<dynamic>>[
        delegate,
        GlobalMaterialLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
      ];

  /// A list of this localizations delegate's supported locales.
  static const List<Locale> supportedLocales = <Locale>[Locale('en')];

  /// Web en.json "accounts.activeTag".
  ///
  /// In en, this message translates to:
  /// **'In use'**
  String get accounts_activeTag;

  /// Web en.json "accounts.add".
  ///
  /// In en, this message translates to:
  /// **'Add an account'**
  String get accounts_add;

  /// Web en.json "accounts.addFirst".
  ///
  /// In en, this message translates to:
  /// **'Add your first account'**
  String get accounts_addFirst;

  /// Web en.json "accounts.addTitle".
  ///
  /// In en, this message translates to:
  /// **'Add an account'**
  String get accounts_addTitle;

  /// Web en.json "accounts.added".
  ///
  /// In en, this message translates to:
  /// **'Account added.'**
  String get accounts_added;

  /// Web en.json "accounts.archive".
  ///
  /// In en, this message translates to:
  /// **'Archive'**
  String get accounts_archive;

  /// Web en.json "accounts.archiveHeading".
  ///
  /// In en, this message translates to:
  /// **'Archive {name}?'**
  String accounts_archiveHeading(String name);

  /// Web en.json "accounts.archiveMessage".
  ///
  /// In en, this message translates to:
  /// **'It keeps its history, hides it from lists, and stops taking new transactions. Its balance leaves the subtotal for its currency, so that figure will change. You can bring it back whenever you like.'**
  String get accounts_archiveMessage;

  /// Web en.json "accounts.archivedHeading".
  ///
  /// In en, this message translates to:
  /// **'Archived'**
  String get accounts_archivedHeading;

  /// Web en.json "accounts.archivedNote".
  ///
  /// In en, this message translates to:
  /// **'These keep their history and take no new transactions.'**
  String get accounts_archivedNote;

  /// Web en.json "accounts.archivedNoteWritable".
  ///
  /// In en, this message translates to:
  /// **'These keep their history and take no new transactions. Bring one back at any time.'**
  String get accounts_archivedNoteWritable;

  /// Web en.json "accounts.archivedNotice".
  ///
  /// In en, this message translates to:
  /// **'That account has been archived.'**
  String get accounts_archivedNotice;

  /// Web en.json "accounts.archivedTag".
  ///
  /// In en, this message translates to:
  /// **'Archived'**
  String get accounts_archivedTag;

  /// Web en.json "accounts.conflictBody".
  ///
  /// In en, this message translates to:
  /// **'Nothing you typed has been saved. This is what the account says now:'**
  String get accounts_conflictBody;

  /// Web en.json "accounts.conflictField".
  ///
  /// In en, this message translates to:
  /// **'{field}: {value}'**
  String accounts_conflictField(String field, String value);

  /// Web en.json "accounts.conflictOverwrite".
  ///
  /// In en, this message translates to:
  /// **'Keep mine and save'**
  String get accounts_conflictOverwrite;

  /// Web en.json "accounts.conflictTitle".
  ///
  /// In en, this message translates to:
  /// **'Somebody else saved first'**
  String get accounts_conflictTitle;

  /// Web en.json "accounts.conflictUseLatest".
  ///
  /// In en, this message translates to:
  /// **'Use theirs'**
  String get accounts_conflictUseLatest;

  /// Web en.json "accounts.currency".
  ///
  /// In en, this message translates to:
  /// **'Currency'**
  String get accounts_currency;

  /// Web en.json "accounts.currencyHint".
  ///
  /// In en, this message translates to:
  /// **'The currency this account is held in. Nothing is ever converted behind your back.'**
  String get accounts_currencyHint;

  /// Web en.json "accounts.currencyLocked".
  ///
  /// In en, this message translates to:
  /// **'The currency cannot be changed once an account has transactions: the amounts already recorded would quietly start meaning something else.'**
  String get accounts_currencyLocked;

  /// Web en.json "accounts.delete".
  ///
  /// In en, this message translates to:
  /// **'Delete'**
  String get accounts_delete;

  /// Web en.json "accounts.deleteHeading".
  ///
  /// In en, this message translates to:
  /// **'Delete {name}?'**
  String accounts_deleteHeading(String name);

  /// Web en.json "accounts.deleteMessage".
  ///
  /// In en, this message translates to:
  /// **'This permanently removes the account, and it cannot be undone. To keep an account\'s history, archive it instead.'**
  String get accounts_deleteMessage;

  /// Web en.json "accounts.deletedNotice".
  ///
  /// In en, this message translates to:
  /// **'That account has been deleted.'**
  String get accounts_deletedNotice;

  /// Web en.json "accounts.edit".
  ///
  /// In en, this message translates to:
  /// **'Edit'**
  String get accounts_edit;

  /// Web en.json "accounts.editTitle".
  ///
  /// In en, this message translates to:
  /// **'Edit account'**
  String get accounts_editTitle;

  /// Web en.json "accounts.emptyBody".
  ///
  /// In en, this message translates to:
  /// **'An account is a place money sits — a current account, a savings pot, a credit card, or cash in a tin. Add the ones you have and you can start recording what happens in them.'**
  String get accounts_emptyBody;

  /// Web en.json "accounts.emptyReadOnly".
  ///
  /// In en, this message translates to:
  /// **'Your role in this household is read-only, so someone else will need to add them.'**
  String get accounts_emptyReadOnly;

  /// Web en.json "accounts.heading".
  ///
  /// In en, this message translates to:
  /// **'Your accounts'**
  String get accounts_heading;

  /// Web en.json "accounts.hideArchived".
  ///
  /// In en, this message translates to:
  /// **'Hide archived'**
  String get accounts_hideArchived;

  /// Web en.json "accounts.import".
  ///
  /// In en, this message translates to:
  /// **'Import'**
  String get accounts_import;

  /// Web en.json "accounts.lastFour".
  ///
  /// In en, this message translates to:
  /// **'Last four digits'**
  String get accounts_lastFour;

  /// Web en.json "accounts.lastFourHint".
  ///
  /// In en, this message translates to:
  /// **'Optional, and only if it has a number. It is shown beside the name so you can tell two similar accounts apart. Clear this box to remove them.'**
  String get accounts_lastFourHint;

  /// Web en.json "accounts.lastFourNone".
  ///
  /// In en, this message translates to:
  /// **'none'**
  String get accounts_lastFourNone;

  /// Web en.json "accounts.lede".
  ///
  /// In en, this message translates to:
  /// **'Where your household\'s money sits. Tell Budget Owl about each account once, and transactions can be recorded against the right one.'**
  String get accounts_lede;

  /// Web en.json "accounts.name".
  ///
  /// In en, this message translates to:
  /// **'Name'**
  String get accounts_name;

  /// Web en.json "accounts.nameHint".
  ///
  /// In en, this message translates to:
  /// **'Whatever you call it. Two accounts may share a name — the last four digits tell them apart.'**
  String get accounts_nameHint;

  /// Web en.json "accounts.nameWithDigits".
  ///
  /// In en, this message translates to:
  /// **'{name} •{digits}'**
  String accounts_nameWithDigits(String name, String digits);

  /// Web en.json "accounts.notShown".
  ///
  /// In en, this message translates to:
  /// **'{count} more accounts are not shown here.'**
  String accounts_notShown(String count);

  /// Web en.json "accounts.notShownOne".
  ///
  /// In en, this message translates to:
  /// **'1 more account is not shown here.'**
  String get accounts_notShownOne;

  /// Web en.json "accounts.openingBalance".
  ///
  /// In en, this message translates to:
  /// **'Opening balance'**
  String get accounts_openingBalance;

  /// Web en.json "accounts.openingBalanceHint".
  ///
  /// In en, this message translates to:
  /// **'What the account held when you started tracking it. A credit card you owe on starts negative, like -400. Use digits and a dot.'**
  String get accounts_openingBalanceHint;

  /// Web en.json "accounts.save".
  ///
  /// In en, this message translates to:
  /// **'Save account'**
  String get accounts_save;

  /// Web en.json "accounts.saved".
  ///
  /// In en, this message translates to:
  /// **'Account saved.'**
  String get accounts_saved;

  /// Web en.json "accounts.showArchived".
  ///
  /// In en, this message translates to:
  /// **'Show {count} archived'**
  String accounts_showArchived(String count);

  /// Web en.json "accounts.state".
  ///
  /// In en, this message translates to:
  /// **'Status'**
  String get accounts_state;

  /// Web en.json "accounts.subtotal".
  ///
  /// In en, this message translates to:
  /// **'Subtotal'**
  String get accounts_subtotal;

  /// Web en.json "accounts.subtotalUnavailable".
  ///
  /// In en, this message translates to:
  /// **'Subtotal unavailable'**
  String get accounts_subtotalUnavailable;

  /// Web en.json "accounts.title".
  ///
  /// In en, this message translates to:
  /// **'Accounts'**
  String get accounts_title;

  /// Web en.json "accounts.type".
  ///
  /// In en, this message translates to:
  /// **'Type'**
  String get accounts_type;

  /// Web en.json "accounts.type.CASH".
  ///
  /// In en, this message translates to:
  /// **'Cash'**
  String get accounts_type_CASH;

  /// Web en.json "accounts.type.CHECKING".
  ///
  /// In en, this message translates to:
  /// **'Current account'**
  String get accounts_type_CHECKING;

  /// Web en.json "accounts.type.CREDIT_CARD".
  ///
  /// In en, this message translates to:
  /// **'Credit card'**
  String get accounts_type_CREDIT_CARD;

  /// Web en.json "accounts.type.INVESTMENT".
  ///
  /// In en, this message translates to:
  /// **'Investment'**
  String get accounts_type_INVESTMENT;

  /// Web en.json "accounts.type.SAVINGS".
  ///
  /// In en, this message translates to:
  /// **'Savings'**
  String get accounts_type_SAVINGS;

  /// Web en.json "accounts.type.unknown".
  ///
  /// In en, this message translates to:
  /// **'Other'**
  String get accounts_type_unknown;

  /// Web en.json "accounts.unarchive".
  ///
  /// In en, this message translates to:
  /// **'Bring back'**
  String get accounts_unarchive;

  /// Web en.json "accounts.unarchivedNotice".
  ///
  /// In en, this message translates to:
  /// **'That account is back in your lists.'**
  String get accounts_unarchivedNotice;

  /// Web en.json "admin.accountCountLabel".
  ///
  /// In en, this message translates to:
  /// **'Accounts'**
  String get admin_accountCountLabel;

  /// Web en.json "admin.accountsEmpty".
  ///
  /// In en, this message translates to:
  /// **'Nobody can sign in.'**
  String get admin_accountsEmpty;

  /// Web en.json "admin.accountsHeading".
  ///
  /// In en, this message translates to:
  /// **'Accounts'**
  String get admin_accountsHeading;

  /// Web en.json "admin.accountsLede".
  ///
  /// In en, this message translates to:
  /// **'Everybody who can sign in to this server. Read-only here — suspending or removing an account is not something this screen does.'**
  String get admin_accountsLede;

  /// Web en.json "admin.administratorLabel".
  ///
  /// In en, this message translates to:
  /// **'Administrator'**
  String get admin_administratorLabel;

  /// Web en.json "admin.administratorTag".
  ///
  /// In en, this message translates to:
  /// **'Administrator'**
  String get admin_administratorTag;

  /// Web en.json "admin.conflictChanged".
  ///
  /// In en, this message translates to:
  /// **'What changed:'**
  String get admin_conflictChanged;

  /// Web en.json "admin.conflictMessage".
  ///
  /// In en, this message translates to:
  /// **'These settings changed after this page read them, so nothing has been saved and what you entered is still here.'**
  String get admin_conflictMessage;

  /// Web en.json "admin.conflictNothingVisible".
  ///
  /// In en, this message translates to:
  /// **'Nothing visible here changed, but somebody saved these settings while you were editing them.'**
  String get admin_conflictNothingVisible;

  /// Web en.json "admin.conflictTitle".
  ///
  /// In en, this message translates to:
  /// **'Somebody else saved first'**
  String get admin_conflictTitle;

  /// Web en.json "admin.field.oidcClientId".
  ///
  /// In en, this message translates to:
  /// **'Client ID'**
  String get admin_field_oidcClientId;

  /// Web en.json "admin.field.oidcClientSecret".
  ///
  /// In en, this message translates to:
  /// **'Client secret'**
  String get admin_field_oidcClientSecret;

  /// Web en.json "admin.field.oidcEnabled".
  ///
  /// In en, this message translates to:
  /// **'Single sign-on'**
  String get admin_field_oidcEnabled;

  /// Web en.json "admin.field.oidcIssuerUri".
  ///
  /// In en, this message translates to:
  /// **'Issuer URL'**
  String get admin_field_oidcIssuerUri;

  /// Web en.json "admin.field.oidcProvisioningEnabled".
  ///
  /// In en, this message translates to:
  /// **'Account creation through the provider'**
  String get admin_field_oidcProvisioningEnabled;

  /// Web en.json "admin.field.passwordLoginEnabled".
  ///
  /// In en, this message translates to:
  /// **'Email and password sign-in'**
  String get admin_field_passwordLoginEnabled;

  /// Web en.json "admin.field.registrationOpen".
  ///
  /// In en, this message translates to:
  /// **'Registration'**
  String get admin_field_registrationOpen;

  /// Web en.json "admin.instanceHeading".
  ///
  /// In en, this message translates to:
  /// **'This instance'**
  String get admin_instanceHeading;

  /// Web en.json "admin.joined".
  ///
  /// In en, this message translates to:
  /// **'Joined {date}'**
  String admin_joined(String date);

  /// Web en.json "admin.keepMine".
  ///
  /// In en, this message translates to:
  /// **'Save my changes anyway'**
  String get admin_keepMine;

  /// Web en.json "admin.lede".
  ///
  /// In en, this message translates to:
  /// **'Who can sign in to this server, and how. Everything else an operator does — backups, upgrades, the database — is done on the machine, because the machine is yours.'**
  String get admin_lede;

  /// Web en.json "admin.nothingChanged".
  ///
  /// In en, this message translates to:
  /// **'Nothing had changed, so nothing was saved.'**
  String get admin_nothingChanged;

  /// Web en.json "admin.oidcClientId".
  ///
  /// In en, this message translates to:
  /// **'Client ID'**
  String get admin_oidcClientId;

  /// Web en.json "admin.oidcClientIdHint".
  ///
  /// In en, this message translates to:
  /// **'The identifier your provider issued for Budget Owl.'**
  String get admin_oidcClientIdHint;

  /// Web en.json "admin.oidcIssuer".
  ///
  /// In en, this message translates to:
  /// **'Issuer URL'**
  String get admin_oidcIssuer;

  /// Web en.json "admin.oidcIssuerHint".
  ///
  /// In en, this message translates to:
  /// **'Where your provider publishes its configuration, such as https://id.example.org. Use https: the client secret is sent to this host.'**
  String get admin_oidcIssuerHint;

  /// Web en.json "admin.oidcLede".
  ///
  /// In en, this message translates to:
  /// **'Optional. If you already run an identity provider, this adds a second way in. It changes nothing about what anybody may see once they are signed in.'**
  String get admin_oidcLede;

  /// Web en.json "admin.oidcLegend".
  ///
  /// In en, this message translates to:
  /// **'Single sign-on'**
  String get admin_oidcLegend;

  /// Web en.json "admin.oidcNotProven".
  ///
  /// In en, this message translates to:
  /// **'No owner has signed in through your provider yet. Until one has, email and password sign-in stays on.'**
  String get admin_oidcNotProven;

  /// Web en.json "admin.oidcOffConfirm".
  ///
  /// In en, this message translates to:
  /// **'Turn it off'**
  String get admin_oidcOffConfirm;

  /// Web en.json "admin.oidcOffHeading".
  ///
  /// In en, this message translates to:
  /// **'Turn off single sign-on?'**
  String get admin_oidcOffHeading;

  /// Web en.json "admin.oidcOffMessage".
  ///
  /// In en, this message translates to:
  /// **'Email and password sign-in will be switched back on, so that nobody is left without a way in. Your provider\'s details are cleared, and anybody who signed in through it will need a password instead.'**
  String get admin_oidcOffMessage;

  /// Web en.json "admin.oidcOffWarning".
  ///
  /// In en, this message translates to:
  /// **'Turning this off also switches email and password sign-in back on, so that nobody is left without a way in.'**
  String get admin_oidcOffWarning;

  /// Web en.json "admin.oidcProven".
  ///
  /// In en, this message translates to:
  /// **'An owner signed in through your provider on {date}, so email and password sign-in can now be turned off.'**
  String admin_oidcProven(String date);

  /// Web en.json "admin.oidcProvisioning".
  ///
  /// In en, this message translates to:
  /// **'Create an account for anybody who signs in through the provider'**
  String get admin_oidcProvisioning;

  /// Web en.json "admin.oidcProvisioningHint".
  ///
  /// In en, this message translates to:
  /// **'An account on its own sees nothing. Joining the household still needs an invitation, so nobody in your provider\'s directory lands inside your finances.'**
  String get admin_oidcProvisioningHint;

  /// Web en.json "admin.oidcSecret".
  ///
  /// In en, this message translates to:
  /// **'Client secret'**
  String get admin_oidcSecret;

  /// Web en.json "admin.oidcSecretHint".
  ///
  /// In en, this message translates to:
  /// **'Budget Owl never shows a stored secret back — not to anybody, including you. Leave this empty to keep the one you have, or type a new one to replace it. If you cannot remember it, replace it.'**
  String get admin_oidcSecretHint;

  /// Web en.json "admin.oidcSwitch".
  ///
  /// In en, this message translates to:
  /// **'Offer sign-in through my provider'**
  String get admin_oidcSwitch;

  /// Web en.json "admin.passwordHint".
  ///
  /// In en, this message translates to:
  /// **'Email and password is a permanent part of Budget Owl. Once another way in is working you can stop offering this one, but it is always in the build and can always be switched back on.'**
  String get admin_passwordHint;

  /// Web en.json "admin.passwordLegend".
  ///
  /// In en, this message translates to:
  /// **'Email and password'**
  String get admin_passwordLegend;

  /// Web en.json "admin.passwordLockedRule".
  ///
  /// In en, this message translates to:
  /// **'An owner has to complete a sign-in through your provider first. Proving the new way in works before the old one is hidden is what stops you being locked out of your own records, on your own hardware, with nobody to call.'**
  String get admin_passwordLockedRule;

  /// Web en.json "admin.passwordLockedTitle".
  ///
  /// In en, this message translates to:
  /// **'This cannot be turned off yet'**
  String get admin_passwordLockedTitle;

  /// Web en.json "admin.passwordOffRecovery".
  ///
  /// In en, this message translates to:
  /// **'If your provider later stops working, this is switched back on from the machine itself. On the server, run:'**
  String get admin_passwordOffRecovery;

  /// Web en.json "admin.passwordOffRecoveryAfter".
  ///
  /// In en, this message translates to:
  /// **'Nothing in the browser can help once the browser is the thing you cannot get in through.'**
  String get admin_passwordOffRecoveryAfter;

  /// Web en.json "admin.passwordOffRecoveryCommand".
  ///
  /// In en, this message translates to:
  /// **'deploy/restore-password-login.sh'**
  String get admin_passwordOffRecoveryCommand;

  /// Web en.json "admin.passwordOn".
  ///
  /// In en, this message translates to:
  /// **'Email and password sign-in is on.'**
  String get admin_passwordOn;

  /// Web en.json "admin.passwordSwitch".
  ///
  /// In en, this message translates to:
  /// **'Offer sign-in with an email address and a password'**
  String get admin_passwordSwitch;

  /// Web en.json "admin.registrationConsequence".
  ///
  /// In en, this message translates to:
  /// **'While registration is open, anybody who can reach this server over the network can create themselves an account. If it is reachable from the internet, that means anybody at all. Leave it closed and invite the people you want instead.'**
  String get admin_registrationConsequence;

  /// Web en.json "admin.registrationLegend".
  ///
  /// In en, this message translates to:
  /// **'Registration'**
  String get admin_registrationLegend;

  /// Web en.json "admin.registrationOpening".
  ///
  /// In en, this message translates to:
  /// **'Saving now will open registration.'**
  String get admin_registrationOpening;

  /// Web en.json "admin.registrationSwitch".
  ///
  /// In en, this message translates to:
  /// **'Let anyone create an account'**
  String get admin_registrationSwitch;

  /// Web en.json "admin.saved".
  ///
  /// In en, this message translates to:
  /// **'Saved.'**
  String get admin_saved;

  /// Web en.json "admin.secretNotSet".
  ///
  /// In en, this message translates to:
  /// **'No secret set.'**
  String get admin_secretNotSet;

  /// Web en.json "admin.secretSet".
  ///
  /// In en, this message translates to:
  /// **'A secret is set.'**
  String get admin_secretSet;

  /// Web en.json "admin.setupLabel".
  ///
  /// In en, this message translates to:
  /// **'Set up'**
  String get admin_setupLabel;

  /// Web en.json "admin.setupUnrecorded".
  ///
  /// In en, this message translates to:
  /// **'Not recorded'**
  String get admin_setupUnrecorded;

  /// Web en.json "admin.signingInHeading".
  ///
  /// In en, this message translates to:
  /// **'Signing in'**
  String get admin_signingInHeading;

  /// Web en.json "admin.status.ACTIVE".
  ///
  /// In en, this message translates to:
  /// **'Active'**
  String get admin_status_ACTIVE;

  /// Web en.json "admin.status.DISABLED".
  ///
  /// In en, this message translates to:
  /// **'Disabled'**
  String get admin_status_DISABLED;

  /// Web en.json "admin.status.unknown".
  ///
  /// In en, this message translates to:
  /// **'Unknown status'**
  String get admin_status_unknown;

  /// Web en.json "admin.title".
  ///
  /// In en, this message translates to:
  /// **'Administration'**
  String get admin_title;

  /// Web en.json "admin.useLatest".
  ///
  /// In en, this message translates to:
  /// **'Take the latest'**
  String get admin_useLatest;

  /// Web en.json "admin.versionLabel".
  ///
  /// In en, this message translates to:
  /// **'Version'**
  String get admin_versionLabel;

  /// Web en.json "app.name".
  ///
  /// In en, this message translates to:
  /// **'Budget Owl'**
  String get app_name;

  /// Web en.json "app.skipToContent".
  ///
  /// In en, this message translates to:
  /// **'Skip to main content'**
  String get app_skipToContent;

  /// Web en.json "bills.actualAmount".
  ///
  /// In en, this message translates to:
  /// **'Amount paid'**
  String get bills_actualAmount;

  /// Web en.json "bills.actualAmountHint".
  ///
  /// In en, this message translates to:
  /// **'What really left the account. The budgeted figure is filled in for you.'**
  String get bills_actualAmountHint;

  /// Web en.json "bills.actualAmountHintBlank".
  ///
  /// In en, this message translates to:
  /// **'What really left the account.'**
  String get bills_actualAmountHintBlank;

  /// Web en.json "bills.add".
  ///
  /// In en, this message translates to:
  /// **'Add a bill'**
  String get bills_add;

  /// Web en.json "bills.addFirst".
  ///
  /// In en, this message translates to:
  /// **'Add your first bill'**
  String get bills_addFirst;

  /// Web en.json "bills.addTitle".
  ///
  /// In en, this message translates to:
  /// **'Add a bill'**
  String get bills_addTitle;

  /// Web en.json "bills.amountVaries".
  ///
  /// In en, this message translates to:
  /// **'The amount varies'**
  String get bills_amountVaries;

  /// Web en.json "bills.amountVariesHint".
  ///
  /// In en, this message translates to:
  /// **'For a bill that is never quite the same twice, like electricity.'**
  String get bills_amountVariesHint;

  /// Web en.json "bills.anchorDate".
  ///
  /// In en, this message translates to:
  /// **'Counting from'**
  String get bills_anchorDate;

  /// Web en.json "bills.anchorDateHint".
  ///
  /// In en, this message translates to:
  /// **'A date it was due. “Every other week” needs one, or it names two different sets of days.'**
  String get bills_anchorDateHint;

  /// Web en.json "bills.billsCountEitherWay".
  ///
  /// In en, this message translates to:
  /// **'Bills count here whether or not you have paid them. Money that has already gone is not money you still have, so ticking one off does not change this figure.'**
  String get bills_billsCountEitherWay;

  /// Web en.json "bills.category".
  ///
  /// In en, this message translates to:
  /// **'Category'**
  String get bills_category;

  /// Web en.json "bills.choose".
  ///
  /// In en, this message translates to:
  /// **'Choose…'**
  String get bills_choose;

  /// Web en.json "bills.created".
  ///
  /// In en, this message translates to:
  /// **'Bill added.'**
  String get bills_created;

  /// Web en.json "bills.dayOfMonth".
  ///
  /// In en, this message translates to:
  /// **'Day of the month'**
  String get bills_dayOfMonth;

  /// Web en.json "bills.dayOfMonthHint".
  ///
  /// In en, this message translates to:
  /// **'The 29th, 30th and 31st fall on the last day of a shorter month.'**
  String get bills_dayOfMonthHint;

  /// Web en.json "bills.dayOfWeek".
  ///
  /// In en, this message translates to:
  /// **'Day of the week'**
  String get bills_dayOfWeek;

  /// Web en.json "bills.debtExtraExceedsLeft".
  ///
  /// In en, this message translates to:
  /// **'Your debt plan puts {extra} a month towards debt beyond the minimums, which is more than the {left} left this month after your goals. Nothing has been changed — adjust the plan or a goal if you need to.'**
  String bills_debtExtraExceedsLeft(String extra, String left);

  /// Web en.json "bills.deleteHeading".
  ///
  /// In en, this message translates to:
  /// **'Delete {name}?'**
  String bills_deleteHeading(String name);

  /// Web en.json "bills.deleteMessage".
  ///
  /// In en, this message translates to:
  /// **'Everything recorded about this bill goes with it. Your transactions are kept — a bill is a plan, not a payment. This can\'t be undone.'**
  String get bills_deleteMessage;

  /// Web en.json "bills.deleted".
  ///
  /// In en, this message translates to:
  /// **'Bill deleted.'**
  String get bills_deleted;

  /// Web en.json "bills.dueDate".
  ///
  /// In en, this message translates to:
  /// **'Date it was due'**
  String get bills_dueDate;

  /// Web en.json "bills.dueDateHint".
  ///
  /// In en, this message translates to:
  /// **'An irregular bill has no date of its own, so this is the date the payment is recorded against.'**
  String get bills_dueDateHint;

  /// Web en.json "bills.editTitle".
  ///
  /// In en, this message translates to:
  /// **'Edit bill'**
  String get bills_editTitle;

  /// Web en.json "bills.emptyBody".
  ///
  /// In en, this message translates to:
  /// **'A bill is something that leaves on a date — rent, a phone contract, a season ticket. Set one up and it is listed before it is due, so nothing surprises you.'**
  String get bills_emptyBody;

  /// Web en.json "bills.emptyReadOnly".
  ///
  /// In en, this message translates to:
  /// **'Ask an owner or member of your household to set some up.'**
  String get bills_emptyReadOnly;

  /// Web en.json "bills.envelopes.allocated".
  ///
  /// In en, this message translates to:
  /// **'Set aside this month'**
  String get bills_envelopes_allocated;

  /// Web en.json "bills.envelopes.body".
  ///
  /// In en, this message translates to:
  /// **'The other half of the subtraction: what you have set aside for the spending that changes week to week. Envelopes have a screen of their own.'**
  String get bills_envelopes_body;

  /// Web en.json "bills.envelopes.heading".
  ///
  /// In en, this message translates to:
  /// **'Envelopes'**
  String get bills_envelopes_heading;

  /// Web en.json "bills.envelopes.open".
  ///
  /// In en, this message translates to:
  /// **'Open envelopes'**
  String get bills_envelopes_open;

  /// Web en.json "bills.expectedAmount".
  ///
  /// In en, this message translates to:
  /// **'Amount'**
  String get bills_expectedAmount;

  /// Web en.json "bills.expectedAmountHint".
  ///
  /// In en, this message translates to:
  /// **'What you expect it to be, in {currency}.'**
  String bills_expectedAmountHint(String currency);

  /// Web en.json "bills.expectedLabel".
  ///
  /// In en, this message translates to:
  /// **'Budgeted'**
  String get bills_expectedLabel;

  /// Web en.json "bills.forget".
  ///
  /// In en, this message translates to:
  /// **'Forget'**
  String get bills_forget;

  /// Web en.json "bills.forgetHeading".
  ///
  /// In en, this message translates to:
  /// **'Forget what was recorded?'**
  String get bills_forgetHeading;

  /// Web en.json "bills.forgetMessage".
  ///
  /// In en, this message translates to:
  /// **'This removes what was recorded for that date, so it reads as planned again. It is not the same as marking it due: that is you saying it is unpaid, and this is saying nothing at all.'**
  String get bills_forgetMessage;

  /// Web en.json "bills.forgotten".
  ///
  /// In en, this message translates to:
  /// **'Forgotten. That date reads as planned again.'**
  String get bills_forgotten;

  /// Web en.json "bills.goalsNote".
  ///
  /// In en, this message translates to:
  /// **'Goals count what you plan to set aside each month, or what you recorded if that is more. Money already saved before you started tracking a goal is never counted.'**
  String get bills_goalsNote;

  /// Web en.json "bills.heading".
  ///
  /// In en, this message translates to:
  /// **'Your bills'**
  String get bills_heading;

  /// Web en.json "bills.howOften".
  ///
  /// In en, this message translates to:
  /// **'How often'**
  String get bills_howOften;

  /// Web en.json "bills.income.account".
  ///
  /// In en, this message translates to:
  /// **'Account it lands in'**
  String get bills_income_account;

  /// Web en.json "bills.income.add".
  ///
  /// In en, this message translates to:
  /// **'Add an income source'**
  String get bills_income_add;

  /// Web en.json "bills.income.addFirst".
  ///
  /// In en, this message translates to:
  /// **'Add your first income source'**
  String get bills_income_addFirst;

  /// Web en.json "bills.income.addTitle".
  ///
  /// In en, this message translates to:
  /// **'Add an income source'**
  String get bills_income_addTitle;

  /// Web en.json "bills.income.amount".
  ///
  /// In en, this message translates to:
  /// **'Amount each time'**
  String get bills_income_amount;

  /// Web en.json "bills.income.amountHint".
  ///
  /// In en, this message translates to:
  /// **'What arrives on each date, in {currency}. The month\'s total is worked out from the schedule.'**
  String bills_income_amountHint(String currency);

  /// Web en.json "bills.income.arrivals".
  ///
  /// In en, this message translates to:
  /// **'{count} arrivals this month'**
  String bills_income_arrivals(String count);

  /// Web en.json "bills.income.arrivalsOne".
  ///
  /// In en, this message translates to:
  /// **'1 arrival this month'**
  String get bills_income_arrivalsOne;

  /// Web en.json "bills.income.created".
  ///
  /// In en, this message translates to:
  /// **'Income source added.'**
  String get bills_income_created;

  /// Web en.json "bills.income.deleteHeading".
  ///
  /// In en, this message translates to:
  /// **'Delete {name}?'**
  String bills_income_deleteHeading(String name);

  /// Web en.json "bills.income.deleteMessage".
  ///
  /// In en, this message translates to:
  /// **'This income source goes, along with what it contributed to any month\'s figures. Your transactions are kept. This can\'t be undone.'**
  String get bills_income_deleteMessage;

  /// Web en.json "bills.income.deleted".
  ///
  /// In en, this message translates to:
  /// **'Income source deleted.'**
  String get bills_income_deleted;

  /// Web en.json "bills.income.eachLabel".
  ///
  /// In en, this message translates to:
  /// **'Each time'**
  String get bills_income_eachLabel;

  /// Web en.json "bills.income.editTitle".
  ///
  /// In en, this message translates to:
  /// **'Edit income source'**
  String get bills_income_editTitle;

  /// Web en.json "bills.income.emptyBody".
  ///
  /// In en, this message translates to:
  /// **'An income source is money arriving on a schedule — a salary, a pension, a regular payment from a tenant. Add one and this page can say what is left once the predictable things are accounted for.'**
  String get bills_income_emptyBody;

  /// Web en.json "bills.income.heading".
  ///
  /// In en, this message translates to:
  /// **'Income'**
  String get bills_income_heading;

  /// Web en.json "bills.income.name".
  ///
  /// In en, this message translates to:
  /// **'Name'**
  String get bills_income_name;

  /// Web en.json "bills.income.nameHint".
  ///
  /// In en, this message translates to:
  /// **'What you would call it out loud — “Salary”, “Pension”.'**
  String get bills_income_nameHint;

  /// Web en.json "bills.income.noAccount".
  ///
  /// In en, this message translates to:
  /// **'No account'**
  String get bills_income_noAccount;

  /// Web en.json "bills.income.restored".
  ///
  /// In en, this message translates to:
  /// **'Income source restored.'**
  String get bills_income_restored;

  /// Web en.json "bills.income.retireHeading".
  ///
  /// In en, this message translates to:
  /// **'Retire {name}?'**
  String bills_income_retireHeading(String name);

  /// Web en.json "bills.income.retireMessage".
  ///
  /// In en, this message translates to:
  /// **'It stops counting towards what is expected in new months. You can restore it at any time.'**
  String get bills_income_retireMessage;

  /// Web en.json "bills.income.retired".
  ///
  /// In en, this message translates to:
  /// **'Income source retired.'**
  String get bills_income_retired;

  /// Web en.json "bills.income.retiredHeading".
  ///
  /// In en, this message translates to:
  /// **'Retired income sources'**
  String get bills_income_retiredHeading;

  /// Web en.json "bills.income.schedule".
  ///
  /// In en, this message translates to:
  /// **'When it arrives'**
  String get bills_income_schedule;

  /// Web en.json "bills.income.total".
  ///
  /// In en, this message translates to:
  /// **'Expected this month'**
  String get bills_income_total;

  /// Web en.json "bills.income.updated".
  ///
  /// In en, this message translates to:
  /// **'Income source saved.'**
  String get bills_income_updated;

  /// Web en.json "bills.irregularHint".
  ///
  /// In en, this message translates to:
  /// **'An irregular bill has no set date. It is listed here, and you record each payment as it happens.'**
  String get bills_irregularHint;

  /// Web en.json "bills.kind.FORTNIGHTLY".
  ///
  /// In en, this message translates to:
  /// **'Every other week'**
  String get bills_kind_FORTNIGHTLY;

  /// Web en.json "bills.kind.IRREGULAR".
  ///
  /// In en, this message translates to:
  /// **'Irregular'**
  String get bills_kind_IRREGULAR;

  /// Web en.json "bills.kind.MONTHLY".
  ///
  /// In en, this message translates to:
  /// **'Every month'**
  String get bills_kind_MONTHLY;

  /// Web en.json "bills.kind.WEEKLY".
  ///
  /// In en, this message translates to:
  /// **'Every week'**
  String get bills_kind_WEEKLY;

  /// Web en.json "bills.kind.YEARLY".
  ///
  /// In en, this message translates to:
  /// **'Every year'**
  String get bills_kind_YEARLY;

  /// Web en.json "bills.kind.unknown".
  ///
  /// In en, this message translates to:
  /// **'On a schedule'**
  String get bills_kind_unknown;

  /// Web en.json "bills.lede".
  ///
  /// In en, this message translates to:
  /// **'The predictable half of your money: what arrives on a schedule, and what leaves on a date. With your envelopes, that is enough to say what is left.'**
  String get bills_lede;

  /// Web en.json "bills.left".
  ///
  /// In en, this message translates to:
  /// **'left to spend'**
  String get bills_left;

  /// Web en.json "bills.leftHeading".
  ///
  /// In en, this message translates to:
  /// **'What\'s left this month'**
  String get bills_leftHeading;

  /// Web en.json "bills.markDue".
  ///
  /// In en, this message translates to:
  /// **'Mark as due'**
  String get bills_markDue;

  /// Web en.json "bills.markPaid".
  ///
  /// In en, this message translates to:
  /// **'Mark paid'**
  String get bills_markPaid;

  /// Web en.json "bills.markedDue".
  ///
  /// In en, this message translates to:
  /// **'Marked as due.'**
  String get bills_markedDue;

  /// Web en.json "bills.markedPaid".
  ///
  /// In en, this message translates to:
  /// **'Marked paid.'**
  String get bills_markedPaid;

  /// Web en.json "bills.month".
  ///
  /// In en, this message translates to:
  /// **'Month'**
  String get bills_month;

  /// Web en.json "bills.moreActions".
  ///
  /// In en, this message translates to:
  /// **'Correct this'**
  String get bills_moreActions;

  /// Web en.json "bills.name".
  ///
  /// In en, this message translates to:
  /// **'Name'**
  String get bills_name;

  /// Web en.json "bills.nameHint".
  ///
  /// In en, this message translates to:
  /// **'What you would call it out loud — “Rent”, “Phone”.'**
  String get bills_nameHint;

  /// Web en.json "bills.nextMonth".
  ///
  /// In en, this message translates to:
  /// **'Next month'**
  String get bills_nextMonth;

  /// Web en.json "bills.noCategory".
  ///
  /// In en, this message translates to:
  /// **'No category'**
  String get bills_noCategory;

  /// Web en.json "bills.noneIrregular".
  ///
  /// In en, this message translates to:
  /// **'No set date, so nothing is due in particular. Record a payment when one happens.'**
  String get bills_noneIrregular;

  /// Web en.json "bills.noneThisMonth".
  ///
  /// In en, this message translates to:
  /// **'Nothing due this month.'**
  String get bills_noneThisMonth;

  /// Web en.json "bills.notCounted".
  ///
  /// In en, this message translates to:
  /// **'not counted this month'**
  String get bills_notCounted;

  /// Web en.json "bills.otherAmount".
  ///
  /// In en, this message translates to:
  /// **'Paid a different amount…'**
  String get bills_otherAmount;

  /// Web en.json "bills.outstandingLabel".
  ///
  /// In en, this message translates to:
  /// **'Still to pay'**
  String get bills_outstandingLabel;

  /// Web en.json "bills.overdue".
  ///
  /// In en, this message translates to:
  /// **'Overdue'**
  String get bills_overdue;

  /// Web en.json "bills.overdueLabel".
  ///
  /// In en, this message translates to:
  /// **'Overdue'**
  String get bills_overdueLabel;

  /// Web en.json "bills.owed.asOfToday".
  ///
  /// In en, this message translates to:
  /// **'These two totals and the count are as they stand today, not as they stood in the month shown — a balance as at a past month would need a day-by-day ledger Budget Owl does not keep.'**
  String get bills_owed_asOfToday;

  /// Web en.json "bills.owed.body".
  ///
  /// In en, this message translates to:
  /// **'What you owe in all. This is a balance, not a monthly commitment, so it is not taken off what is left — doing that would tell a household owing a year\'s wages that it has nothing to spend this month.'**
  String get bills_owed_body;

  /// Web en.json "bills.owed.count".
  ///
  /// In en, this message translates to:
  /// **'Across {count} debts'**
  String bills_owed_count(String count);

  /// Web en.json "bills.owed.countOne".
  ///
  /// In en, this message translates to:
  /// **'On one debt'**
  String get bills_owed_countOne;

  /// Web en.json "bills.owed.heading".
  ///
  /// In en, this message translates to:
  /// **'Owed'**
  String get bills_owed_heading;

  /// Web en.json "bills.owed.minimums".
  ///
  /// In en, this message translates to:
  /// **'Minimums a month'**
  String get bills_owed_minimums;

  /// Web en.json "bills.owed.mixedCurrency".
  ///
  /// In en, this message translates to:
  /// **'The count covers debts in every currency; the two totals are {currency} only. They do not describe the same set of debts, because adding currencies together would need a rate this instance may not have.'**
  String bills_owed_mixedCurrency(String currency);

  /// Web en.json "bills.owed.none".
  ///
  /// In en, this message translates to:
  /// **'No debts recorded, so nothing here comes off what is left.'**
  String get bills_owed_none;

  /// Web en.json "bills.owed.notInBills".
  ///
  /// In en, this message translates to:
  /// **'{amount} of those minimums is not already a bill row this month, and that is the part taken off what is left. The rest is already counted in your bills.'**
  String bills_owed_notInBills(String amount);

  /// Web en.json "bills.owed.open".
  ///
  /// In en, this message translates to:
  /// **'Open the debt plan'**
  String get bills_owed_open;

  /// Web en.json "bills.owed.total".
  ///
  /// In en, this message translates to:
  /// **'Owed in all'**
  String get bills_owed_total;

  /// Web en.json "bills.paidDate".
  ///
  /// In en, this message translates to:
  /// **'Date paid'**
  String get bills_paidDate;

  /// Web en.json "bills.paidLabel".
  ///
  /// In en, this message translates to:
  /// **'Paid'**
  String get bills_paidLabel;

  /// Web en.json "bills.paidOnLabel".
  ///
  /// In en, this message translates to:
  /// **'Paid on'**
  String get bills_paidOnLabel;

  /// Web en.json "bills.paidTitle".
  ///
  /// In en, this message translates to:
  /// **'What did you pay for {name}?'**
  String bills_paidTitle(String name);

  /// Web en.json "bills.periodLabel".
  ///
  /// In en, this message translates to:
  /// **'Choose a month'**
  String get bills_periodLabel;

  /// Web en.json "bills.previousMonth".
  ///
  /// In en, this message translates to:
  /// **'Previous month'**
  String get bills_previousMonth;

  /// Web en.json "bills.recordIt".
  ///
  /// In en, this message translates to:
  /// **'Record it'**
  String get bills_recordIt;

  /// Web en.json "bills.recordPayment".
  ///
  /// In en, this message translates to:
  /// **'Record a payment'**
  String get bills_recordPayment;

  /// Web en.json "bills.recordTitle".
  ///
  /// In en, this message translates to:
  /// **'Record a payment for {name}'**
  String bills_recordTitle(String name);

  /// Web en.json "bills.restore".
  ///
  /// In en, this message translates to:
  /// **'Restore'**
  String get bills_restore;

  /// Web en.json "bills.restored".
  ///
  /// In en, this message translates to:
  /// **'Bill restored.'**
  String get bills_restored;

  /// Web en.json "bills.retire".
  ///
  /// In en, this message translates to:
  /// **'Retire'**
  String get bills_retire;

  /// Web en.json "bills.retireHeading".
  ///
  /// In en, this message translates to:
  /// **'Retire {name}?'**
  String bills_retireHeading(String name);

  /// Web en.json "bills.retireMessage".
  ///
  /// In en, this message translates to:
  /// **'It stops being listed in new months and keeps every payment you have already recorded. You can restore it at any time.'**
  String get bills_retireMessage;

  /// Web en.json "bills.retired".
  ///
  /// In en, this message translates to:
  /// **'Bill retired.'**
  String get bills_retired;

  /// Web en.json "bills.retiredHeading".
  ///
  /// In en, this message translates to:
  /// **'Retired bills'**
  String get bills_retiredHeading;

  /// Web en.json "bills.retiredNote".
  ///
  /// In en, this message translates to:
  /// **'These are not listed in new months, and everything recorded against them is kept.'**
  String get bills_retiredNote;

  /// Web en.json "bills.said.FORTNIGHTLY".
  ///
  /// In en, this message translates to:
  /// **'Every other {weekday}, counting from {from}'**
  String bills_said_FORTNIGHTLY(String weekday, String from);

  /// Web en.json "bills.said.IRREGULAR".
  ///
  /// In en, this message translates to:
  /// **'Irregular — no set date'**
  String get bills_said_IRREGULAR;

  /// Web en.json "bills.said.MONTHLY".
  ///
  /// In en, this message translates to:
  /// **'Every month on day {day}'**
  String bills_said_MONTHLY(String day);

  /// Web en.json "bills.said.WEEKLY".
  ///
  /// In en, this message translates to:
  /// **'Every {weekday}'**
  String bills_said_WEEKLY(String weekday);

  /// Web en.json "bills.said.YEARLY".
  ///
  /// In en, this message translates to:
  /// **'Every year on {date}'**
  String bills_said_YEARLY(String date);

  /// Web en.json "bills.schedule".
  ///
  /// In en, this message translates to:
  /// **'When it is due'**
  String get bills_schedule;

  /// Web en.json "bills.short".
  ///
  /// In en, this message translates to:
  /// **'short this month'**
  String get bills_short;

  /// Web en.json "bills.shortNote".
  ///
  /// In en, this message translates to:
  /// **'Your income this month does not cover the bills and what you have set aside. Nothing is wrong with the figures — they are telling you something.'**
  String get bills_shortNote;

  /// Web en.json "bills.skip".
  ///
  /// In en, this message translates to:
  /// **'Skip'**
  String get bills_skip;

  /// Web en.json "bills.skipped".
  ///
  /// In en, this message translates to:
  /// **'Skipped for this month.'**
  String get bills_skipped;

  /// Web en.json "bills.source.AUTO_MATCHED".
  ///
  /// In en, this message translates to:
  /// **'Matched automatically'**
  String get bills_source_AUTO_MATCHED;

  /// Web en.json "bills.source.MANUAL".
  ///
  /// In en, this message translates to:
  /// **'You recorded this'**
  String get bills_source_MANUAL;

  /// Web en.json "bills.source.unknown".
  ///
  /// In en, this message translates to:
  /// **'Recorded'**
  String get bills_source_unknown;

  /// Web en.json "bills.status.DUE".
  ///
  /// In en, this message translates to:
  /// **'Due'**
  String get bills_status_DUE;

  /// Web en.json "bills.status.PAID".
  ///
  /// In en, this message translates to:
  /// **'Paid'**
  String get bills_status_PAID;

  /// Web en.json "bills.status.SKIPPED".
  ///
  /// In en, this message translates to:
  /// **'Skipped'**
  String get bills_status_SKIPPED;

  /// Web en.json "bills.status.unknown".
  ///
  /// In en, this message translates to:
  /// **'Recorded'**
  String get bills_status_unknown;

  /// Web en.json "bills.stillToPayOf".
  ///
  /// In en, this message translates to:
  /// **'{outstanding} of {total} still to pay'**
  String bills_stillToPayOf(String outstanding, String total);

  /// Web en.json "bills.surprising".
  ///
  /// In en, this message translates to:
  /// **'Not what was budgeted'**
  String get bills_surprising;

  /// Web en.json "bills.termBills".
  ///
  /// In en, this message translates to:
  /// **'Bills this month'**
  String get bills_termBills;

  /// Web en.json "bills.termDebtMinimums".
  ///
  /// In en, this message translates to:
  /// **'Debt minimums'**
  String get bills_termDebtMinimums;

  /// Web en.json "bills.termEnvelopes".
  ///
  /// In en, this message translates to:
  /// **'Set aside in envelopes'**
  String get bills_termEnvelopes;

  /// Web en.json "bills.termGoals".
  ///
  /// In en, this message translates to:
  /// **'Goals'**
  String get bills_termGoals;

  /// Web en.json "bills.termIncome".
  ///
  /// In en, this message translates to:
  /// **'Income expected'**
  String get bills_termIncome;

  /// Web en.json "bills.title".
  ///
  /// In en, this message translates to:
  /// **'Bills & income'**
  String get bills_title;

  /// Web en.json "bills.tolerance".
  ///
  /// In en, this message translates to:
  /// **'Give or take'**
  String get bills_tolerance;

  /// Web en.json "bills.toleranceHint".
  ///
  /// In en, this message translates to:
  /// **'How far from the expected amount is unremarkable, in {currency}. A payment further out than that is flagged.'**
  String bills_toleranceHint(String currency);

  /// Web en.json "bills.totalLabel".
  ///
  /// In en, this message translates to:
  /// **'Bills this month'**
  String get bills_totalLabel;

  /// Web en.json "bills.truth.AUTO".
  ///
  /// In en, this message translates to:
  /// **'From your history'**
  String get bills_truth_AUTO;

  /// Web en.json "bills.truth.MANUAL_ESTIMATE".
  ///
  /// In en, this message translates to:
  /// **'Your estimate'**
  String get bills_truth_MANUAL_ESTIMATE;

  /// Web en.json "bills.truth.unknown".
  ///
  /// In en, this message translates to:
  /// **'Estimate'**
  String get bills_truth_unknown;

  /// Web en.json "bills.updated".
  ///
  /// In en, this message translates to:
  /// **'Bill saved.'**
  String get bills_updated;

  /// Web en.json "bills.variesBy".
  ///
  /// In en, this message translates to:
  /// **'Varies by up to'**
  String get bills_variesBy;

  /// Web en.json "categories.add".
  ///
  /// In en, this message translates to:
  /// **'Add a category'**
  String get categories_add;

  /// Web en.json "categories.addChild".
  ///
  /// In en, this message translates to:
  /// **'Add a sub-category'**
  String get categories_addChild;

  /// Web en.json "categories.addFirst".
  ///
  /// In en, this message translates to:
  /// **'Add your first category'**
  String get categories_addFirst;

  /// Web en.json "categories.addTitle".
  ///
  /// In en, this message translates to:
  /// **'Add a category'**
  String get categories_addTitle;

  /// Web en.json "categories.archive".
  ///
  /// In en, this message translates to:
  /// **'Archive'**
  String get categories_archive;

  /// Web en.json "categories.archiveHeading".
  ///
  /// In en, this message translates to:
  /// **'Archive {name}?'**
  String categories_archiveHeading(String name);

  /// Web en.json "categories.archiveMessage".
  ///
  /// In en, this message translates to:
  /// **'It stops appearing when you record a transaction, and your past transactions still show it. You can restore it at any time.'**
  String get categories_archiveMessage;

  /// Web en.json "categories.archived".
  ///
  /// In en, this message translates to:
  /// **'Category archived.'**
  String get categories_archived;

  /// Web en.json "categories.archivedHeading".
  ///
  /// In en, this message translates to:
  /// **'Archived categories'**
  String get categories_archivedHeading;

  /// Web en.json "categories.archivedNote".
  ///
  /// In en, this message translates to:
  /// **'These don\'t appear when you record a transaction, but your past transactions still show them.'**
  String get categories_archivedNote;

  /// Web en.json "categories.childLabel".
  ///
  /// In en, this message translates to:
  /// **'{parent} → {name}'**
  String categories_childLabel(String parent, String name);

  /// Web en.json "categories.created".
  ///
  /// In en, this message translates to:
  /// **'Category added.'**
  String get categories_created;

  /// Web en.json "categories.delete".
  ///
  /// In en, this message translates to:
  /// **'Delete'**
  String get categories_delete;

  /// Web en.json "categories.deleteHeading".
  ///
  /// In en, this message translates to:
  /// **'Delete {name}?'**
  String categories_deleteHeading(String name);

  /// Web en.json "categories.deleteMessage".
  ///
  /// In en, this message translates to:
  /// **'Your transactions are kept — they\'ll just need a category again. This can\'t be undone.'**
  String get categories_deleteMessage;

  /// Web en.json "categories.deleteMessageWithChildren".
  ///
  /// In en, this message translates to:
  /// **'Its {count} sub-categories are deleted too. Your transactions are kept — they\'ll just need a category again. This can\'t be undone.'**
  String categories_deleteMessageWithChildren(String count);

  /// Web en.json "categories.deleted".
  ///
  /// In en, this message translates to:
  /// **'Category deleted.'**
  String get categories_deleted;

  /// Web en.json "categories.editTitle".
  ///
  /// In en, this message translates to:
  /// **'Edit category'**
  String get categories_editTitle;

  /// Web en.json "categories.emptyBody".
  ///
  /// In en, this message translates to:
  /// **'Categories let you see where your money goes. You started with a set — if you\'ve deleted them all, add the ones you want.'**
  String get categories_emptyBody;

  /// Web en.json "categories.emptyReadOnly".
  ///
  /// In en, this message translates to:
  /// **'Ask an owner or member of your household to add some.'**
  String get categories_emptyReadOnly;

  /// Web en.json "categories.hasChildrenHint".
  ///
  /// In en, this message translates to:
  /// **'This category has sub-categories, so it can\'t sit under another one.'**
  String get categories_hasChildrenHint;

  /// Web en.json "categories.heading".
  ///
  /// In en, this message translates to:
  /// **'Your categories'**
  String get categories_heading;

  /// Web en.json "categories.hideArchived".
  ///
  /// In en, this message translates to:
  /// **'Hide {count} archived'**
  String categories_hideArchived(String count);

  /// Web en.json "categories.kind".
  ///
  /// In en, this message translates to:
  /// **'Kind'**
  String get categories_kind;

  /// Web en.json "categories.kind.EXPENSE".
  ///
  /// In en, this message translates to:
  /// **'Spending'**
  String get categories_kind_EXPENSE;

  /// Web en.json "categories.kind.INCOME".
  ///
  /// In en, this message translates to:
  /// **'Income'**
  String get categories_kind_INCOME;

  /// Web en.json "categories.kind.unknown".
  ///
  /// In en, this message translates to:
  /// **'Other'**
  String get categories_kind_unknown;

  /// Web en.json "categories.kindFixedHint".
  ///
  /// In en, this message translates to:
  /// **'A category\'s kind can\'t change, because that would reinterpret everything already filed under it.'**
  String get categories_kindFixedHint;

  /// Web en.json "categories.kindFromParentHint".
  ///
  /// In en, this message translates to:
  /// **'A sub-category uses the same kind as the category it sits under.'**
  String get categories_kindFromParentHint;

  /// Web en.json "categories.lede".
  ///
  /// In en, this message translates to:
  /// **'How you group what your money was for. Edit these to match how you actually think about your spending.'**
  String get categories_lede;

  /// Web en.json "categories.name".
  ///
  /// In en, this message translates to:
  /// **'Name'**
  String get categories_name;

  /// Web en.json "categories.noParent".
  ///
  /// In en, this message translates to:
  /// **'Nothing — it\'s a top-level category'**
  String get categories_noParent;

  /// Web en.json "categories.parent".
  ///
  /// In en, this message translates to:
  /// **'Sits under'**
  String get categories_parent;

  /// Web en.json "categories.parentHint".
  ///
  /// In en, this message translates to:
  /// **'Categories can be two levels deep, like Bills then Utilities.'**
  String get categories_parentHint;

  /// Web en.json "categories.showArchived".
  ///
  /// In en, this message translates to:
  /// **'Show {count} archived'**
  String categories_showArchived(String count);

  /// Web en.json "categories.title".
  ///
  /// In en, this message translates to:
  /// **'Categories'**
  String get categories_title;

  /// Web en.json "categories.unarchive".
  ///
  /// In en, this message translates to:
  /// **'Restore'**
  String get categories_unarchive;

  /// Web en.json "categories.unarchived".
  ///
  /// In en, this message translates to:
  /// **'Category restored.'**
  String get categories_unarchived;

  /// Web en.json "categories.updated".
  ///
  /// In en, this message translates to:
  /// **'Category saved.'**
  String get categories_updated;

  /// Web en.json "checkIn.account".
  ///
  /// In en, this message translates to:
  /// **'{name} •{lastFour}'**
  String checkIn_account(String name, String lastFour);

  /// Web en.json "checkIn.accountNoDigits".
  ///
  /// In en, this message translates to:
  /// **'{name}'**
  String checkIn_accountNoDigits(String name);

  /// Web en.json "checkIn.answerConflict".
  ///
  /// In en, this message translates to:
  /// **'Someone else answered this first. Their answer is shown below.'**
  String get checkIn_answerConflict;

  /// Web en.json "checkIn.answeredBy".
  ///
  /// In en, this message translates to:
  /// **'Answered by {name}'**
  String checkIn_answeredBy(String name);

  /// Web en.json "checkIn.back".
  ///
  /// In en, this message translates to:
  /// **'Back'**
  String get checkIn_back;

  /// Web en.json "checkIn.bills.amount".
  ///
  /// In en, this message translates to:
  /// **'Amount'**
  String get checkIn_bills_amount;

  /// Web en.json "checkIn.bills.bill".
  ///
  /// In en, this message translates to:
  /// **'Bill'**
  String get checkIn_bills_bill;

  /// Web en.json "checkIn.bills.date".
  ///
  /// In en, this message translates to:
  /// **'Date'**
  String get checkIn_bills_date;

  /// Web en.json "checkIn.bills.due".
  ///
  /// In en, this message translates to:
  /// **'Due {date}'**
  String checkIn_bills_due(String date);

  /// Web en.json "checkIn.bills.heading".
  ///
  /// In en, this message translates to:
  /// **'Bills this month'**
  String get checkIn_bills_heading;

  /// Web en.json "checkIn.bills.mapHeading".
  ///
  /// In en, this message translates to:
  /// **'One to map'**
  String get checkIn_bills_mapHeading;

  /// Web en.json "checkIn.bills.overdue".
  ///
  /// In en, this message translates to:
  /// **'Overdue'**
  String get checkIn_bills_overdue;

  /// Web en.json "checkIn.bills.paid".
  ///
  /// In en, this message translates to:
  /// **'Paid'**
  String get checkIn_bills_paid;

  /// Web en.json "checkIn.bills.skipped".
  ///
  /// In en, this message translates to:
  /// **'Skipped'**
  String get checkIn_bills_skipped;

  /// Web en.json "checkIn.bills.status".
  ///
  /// In en, this message translates to:
  /// **'Status'**
  String get checkIn_bills_status;

  /// Web en.json "checkIn.bills.stillToPay".
  ///
  /// In en, this message translates to:
  /// **'Still to leave this month'**
  String get checkIn_bills_stillToPay;

  /// Web en.json "checkIn.categorize.always".
  ///
  /// In en, this message translates to:
  /// **'Always file this payee here'**
  String get checkIn_categorize_always;

  /// Web en.json "checkIn.categorize.category".
  ///
  /// In en, this message translates to:
  /// **'Category'**
  String get checkIn_categorize_category;

  /// Web en.json "checkIn.categorize.categoryPath".
  ///
  /// In en, this message translates to:
  /// **'{parent} → {name}'**
  String checkIn_categorize_categoryPath(String parent, String name);

  /// Web en.json "checkIn.categorize.choose".
  ///
  /// In en, this message translates to:
  /// **'Choose a category'**
  String get checkIn_categorize_choose;

  /// Web en.json "checkIn.categorize.heading".
  ///
  /// In en, this message translates to:
  /// **'{count} charges need a category'**
  String checkIn_categorize_heading(String count);

  /// Web en.json "checkIn.categorize.headingOne".
  ///
  /// In en, this message translates to:
  /// **'1 charge needs a category'**
  String get checkIn_categorize_headingOne;

  /// Web en.json "checkIn.categorize.headingPlaced".
  ///
  /// In en, this message translates to:
  /// **'Placed by your rules'**
  String get checkIn_categorize_headingPlaced;

  /// Web en.json "checkIn.categorize.more".
  ///
  /// In en, this message translates to:
  /// **'And {count} more in the ledger.'**
  String checkIn_categorize_more(String count);

  /// Web en.json "checkIn.categorize.moreLink".
  ///
  /// In en, this message translates to:
  /// **'See them all'**
  String get checkIn_categorize_moreLink;

  /// Web en.json "checkIn.categorize.placed".
  ///
  /// In en, this message translates to:
  /// **'{placed} of {total} placed'**
  String checkIn_categorize_placed(String placed, String total);

  /// Web en.json "checkIn.categorize.replacesRule".
  ///
  /// In en, this message translates to:
  /// **'Replaces your rule filing it under {category}.'**
  String checkIn_categorize_replacesRule(String category);

  /// Web en.json "checkIn.charge.attach".
  ///
  /// In en, this message translates to:
  /// **'Attach'**
  String get checkIn_charge_attach;

  /// Web en.json "checkIn.charge.attachChosen".
  ///
  /// In en, this message translates to:
  /// **'Attach to this bill'**
  String get checkIn_charge_attachChosen;

  /// Web en.json "checkIn.charge.attachedTo".
  ///
  /// In en, this message translates to:
  /// **'Attached to {bill}, due {date}'**
  String checkIn_charge_attachedTo(String bill, String date);

  /// Web en.json "checkIn.charge.differentBill".
  ///
  /// In en, this message translates to:
  /// **'Different bill'**
  String get checkIn_charge_differentBill;

  /// Web en.json "checkIn.charge.mapIt".
  ///
  /// In en, this message translates to:
  /// **'Yes, map it'**
  String get checkIn_charge_mapIt;

  /// Web en.json "checkIn.charge.matched".
  ///
  /// In en, this message translates to:
  /// **'Matched to {bill} by your mapping.'**
  String checkIn_charge_matched(String bill);

  /// Web en.json "checkIn.charge.notABill".
  ///
  /// In en, this message translates to:
  /// **'Not a bill'**
  String get checkIn_charge_notABill;

  /// Web en.json "checkIn.charge.notABillChosen".
  ///
  /// In en, this message translates to:
  /// **'Not a bill'**
  String get checkIn_charge_notABillChosen;

  /// Web en.json "checkIn.charge.outside".
  ///
  /// In en, this message translates to:
  /// **'Charged {amount}. Your {bill} bill is {expected}. Attach it?'**
  String checkIn_charge_outside(String amount, String bill, String expected);

  /// Web en.json "checkIn.charge.pickBill".
  ///
  /// In en, this message translates to:
  /// **'Choose a bill'**
  String get checkIn_charge_pickBill;

  /// Web en.json "checkIn.charge.recurring".
  ///
  /// In en, this message translates to:
  /// **'A repeating {amount} has hit {account} three months running. Map it to your {bill} bill?'**
  String checkIn_charge_recurring(String amount, String account, String bill);

  /// Web en.json "checkIn.charge.recurringPick".
  ///
  /// In en, this message translates to:
  /// **'A repeating {amount} has hit {account} three months running. Which bill is it?'**
  String checkIn_charge_recurringPick(String amount, String account);

  /// Web en.json "checkIn.charge.remember".
  ///
  /// In en, this message translates to:
  /// **'Remember: this payee in this account pays this bill'**
  String get checkIn_charge_remember;

  /// Web en.json "checkIn.charge.whichBill".
  ///
  /// In en, this message translates to:
  /// **'Which bill'**
  String get checkIn_charge_whichBill;

  /// Web en.json "checkIn.charge.whichDate".
  ///
  /// In en, this message translates to:
  /// **'Which due date'**
  String get checkIn_charge_whichDate;

  /// Web en.json "checkIn.chipDone".
  ///
  /// In en, this message translates to:
  /// **'{number} · done'**
  String checkIn_chipDone(String number);

  /// Web en.json "checkIn.chipName".
  ///
  /// In en, this message translates to:
  /// **'{number} · {name}'**
  String checkIn_chipName(String number, String name);

  /// Web en.json "checkIn.choice.debt".
  ///
  /// In en, this message translates to:
  /// **'Pay down {debt}'**
  String checkIn_choice_debt(String debt);

  /// Web en.json "checkIn.choice.debtMoves".
  ///
  /// In en, this message translates to:
  /// **'Moves {debt} from {from} to {to}.'**
  String checkIn_choice_debtMoves(String debt, String from, String to);

  /// Web en.json "checkIn.choice.debtNeverMoves".
  ///
  /// In en, this message translates to:
  /// **'Records a {amount} payment to {debt} today. Make the payment at your bank — Budget Owl never moves money.'**
  String checkIn_choice_debtNeverMoves(String amount, String debt);

  /// Web en.json "checkIn.choice.debtNoDate".
  ///
  /// In en, this message translates to:
  /// **'{debt} has no payoff month to move yet.'**
  String checkIn_choice_debtNoDate(String debt);

  /// Web en.json "checkIn.choice.debtSameMonth".
  ///
  /// In en, this message translates to:
  /// **'Doesn\'t move {debt}\'s payoff month.'**
  String checkIn_choice_debtSameMonth(String debt);

  /// Web en.json "checkIn.choice.goal".
  ///
  /// In en, this message translates to:
  /// **'Put it towards {goal}'**
  String checkIn_choice_goal(String goal);

  /// Web en.json "checkIn.choice.goalMoves".
  ///
  /// In en, this message translates to:
  /// **'{goal} · {from} → {to} of {target}'**
  String checkIn_choice_goalMoves(String goal, String from, String to, String target);

  /// Web en.json "checkIn.choice.goalNeverMoves".
  ///
  /// In en, this message translates to:
  /// **'Records {amount} set aside for {goal} today. Move the money yourself — Budget Owl never moves money.'**
  String checkIn_choice_goalNeverMoves(String amount, String goal);

  /// Web en.json "checkIn.choice.rollOver".
  ///
  /// In en, this message translates to:
  /// **'Weekly target becomes {amount}.'**
  String checkIn_choice_rollOver(String amount);

  /// Web en.json "checkIn.choice.rollOverLabel".
  ///
  /// In en, this message translates to:
  /// **'Roll it into next week'**
  String get checkIn_choice_rollOverLabel;

  /// Web en.json "checkIn.choiceName.ATTACH".
  ///
  /// In en, this message translates to:
  /// **'Attach'**
  String get checkIn_choiceName_ATTACH;

  /// Web en.json "checkIn.choiceName.CATEGORY".
  ///
  /// In en, this message translates to:
  /// **'A category'**
  String get checkIn_choiceName_CATEGORY;

  /// Web en.json "checkIn.choiceName.DEBT".
  ///
  /// In en, this message translates to:
  /// **'A debt'**
  String get checkIn_choiceName_DEBT;

  /// Web en.json "checkIn.choiceName.GOAL".
  ///
  /// In en, this message translates to:
  /// **'A goal'**
  String get checkIn_choiceName_GOAL;

  /// Web en.json "checkIn.choiceName.LEAVE".
  ///
  /// In en, this message translates to:
  /// **'Left as it is'**
  String get checkIn_choiceName_LEAVE;

  /// Web en.json "checkIn.choiceName.MARK_PAID".
  ///
  /// In en, this message translates to:
  /// **'Mark paid'**
  String get checkIn_choiceName_MARK_PAID;

  /// Web en.json "checkIn.choiceName.NOT_A_BILL".
  ///
  /// In en, this message translates to:
  /// **'Not a bill'**
  String get checkIn_choiceName_NOT_A_BILL;

  /// Web en.json "checkIn.choiceName.PREPAID".
  ///
  /// In en, this message translates to:
  /// **'Prepaid'**
  String get checkIn_choiceName_PREPAID;

  /// Web en.json "checkIn.choiceName.ROLL_OVER".
  ///
  /// In en, this message translates to:
  /// **'Next week'**
  String get checkIn_choiceName_ROLL_OVER;

  /// Web en.json "checkIn.choiceName.SKIP".
  ///
  /// In en, this message translates to:
  /// **'Skip this month'**
  String get checkIn_choiceName_SKIP;

  /// Web en.json "checkIn.chosen".
  ///
  /// In en, this message translates to:
  /// **'Chosen: {choice}'**
  String checkIn_chosen(String choice);

  /// Web en.json "checkIn.clear".
  ///
  /// In en, this message translates to:
  /// **'Clear answer'**
  String get checkIn_clear;

  /// Web en.json "checkIn.completed".
  ///
  /// In en, this message translates to:
  /// **'Check-in done'**
  String get checkIn_completed;

  /// Web en.json "checkIn.completedBy".
  ///
  /// In en, this message translates to:
  /// **'Finished by {name} on {date}'**
  String checkIn_completedBy(String name, String date);

  /// Web en.json "checkIn.completedLede".
  ///
  /// In en, this message translates to:
  /// **'Here\'s what it recorded. Each record lives where it always does, and is corrected there.'**
  String get checkIn_completedLede;

  /// Web en.json "checkIn.discard".
  ///
  /// In en, this message translates to:
  /// **'Discard this draft'**
  String get checkIn_discard;

  /// Web en.json "checkIn.discardMessage".
  ///
  /// In en, this message translates to:
  /// **'Every answer in it is deleted. Nothing else changes, because nothing was recorded yet.'**
  String get checkIn_discardMessage;

  /// Web en.json "checkIn.discardTitle".
  ///
  /// In en, this message translates to:
  /// **'Discard this check-in?'**
  String get checkIn_discardTitle;

  /// Web en.json "checkIn.discarded".
  ///
  /// In en, this message translates to:
  /// **'The draft was discarded.'**
  String get checkIn_discarded;

  /// Web en.json "checkIn.draftOpen".
  ///
  /// In en, this message translates to:
  /// **'There\'s an unfinished check-in for {week}. Finish it or discard it before starting this one.'**
  String checkIn_draftOpen(String week);

  /// Web en.json "checkIn.draftOpenDiscard".
  ///
  /// In en, this message translates to:
  /// **'Discard it'**
  String get checkIn_draftOpenDiscard;

  /// Web en.json "checkIn.draftOpenOpen".
  ///
  /// In en, this message translates to:
  /// **'Open it'**
  String get checkIn_draftOpenOpen;

  /// Web en.json "checkIn.eyebrow".
  ///
  /// In en, this message translates to:
  /// **'Sunday check-in · week {week} · {range}'**
  String checkIn_eyebrow(String week, String range);

  /// Web en.json "checkIn.eyebrowShort".
  ///
  /// In en, this message translates to:
  /// **'Sunday check-in · week {week}'**
  String checkIn_eyebrowShort(String week);

  /// Web en.json "checkIn.finish".
  ///
  /// In en, this message translates to:
  /// **'Finish check-in'**
  String get checkIn_finish;

  /// Web en.json "checkIn.finishMobile".
  ///
  /// In en, this message translates to:
  /// **'Done for the week'**
  String get checkIn_finishMobile;

  /// Web en.json "checkIn.finishing".
  ///
  /// In en, this message translates to:
  /// **'Finishing…'**
  String get checkIn_finishing;

  /// Web en.json "checkIn.income.asExpected".
  ///
  /// In en, this message translates to:
  /// **'As expected. Nothing to do.'**
  String get checkIn_income_asExpected;

  /// Web en.json "checkIn.income.expected".
  ///
  /// In en, this message translates to:
  /// **'Your plan expects {amount}. Nothing to do.'**
  String checkIn_income_expected(String amount);

  /// Web en.json "checkIn.income.heading".
  ///
  /// In en, this message translates to:
  /// **'Paycheck landed'**
  String get checkIn_income_heading;

  /// Web en.json "checkIn.income.headingNotYet".
  ///
  /// In en, this message translates to:
  /// **'Paycheck not seen yet'**
  String get checkIn_income_headingNotYet;

  /// Web en.json "checkIn.income.landed".
  ///
  /// In en, this message translates to:
  /// **'{name} · {date} · {amount}'**
  String checkIn_income_landed(String name, String date, String amount);

  /// Web en.json "checkIn.income.notYet".
  ///
  /// In en, this message translates to:
  /// **'{name}: expected {date}, not seen yet. Nothing to do.'**
  String checkIn_income_notYet(String name, String date);

  /// Web en.json "checkIn.keptAsYouGo".
  ///
  /// In en, this message translates to:
  /// **'Your answers are kept as you go, on any device. Nothing changes in your budget until you finish.'**
  String get checkIn_keptAsYouGo;

  /// Web en.json "checkIn.leaveIt".
  ///
  /// In en, this message translates to:
  /// **'Leave it'**
  String get checkIn_leaveIt;

  /// Web en.json "checkIn.mapping.exact".
  ///
  /// In en, this message translates to:
  /// **'exact match'**
  String get checkIn_mapping_exact;

  /// Web en.json "checkIn.mapping.label".
  ///
  /// In en, this message translates to:
  /// **'Mapped'**
  String get checkIn_mapping_label;

  /// Web en.json "checkIn.mapping.removed".
  ///
  /// In en, this message translates to:
  /// **'The mapping was removed. The next check-in will ask about that charge again.'**
  String get checkIn_mapping_removed;

  /// Web en.json "checkIn.mapping.unmap".
  ///
  /// In en, this message translates to:
  /// **'Unmap'**
  String get checkIn_mapping_unmap;

  /// Web en.json "checkIn.mapping.varies".
  ///
  /// In en, this message translates to:
  /// **'amount varies ±{amount}'**
  String checkIn_mapping_varies(String amount);

  /// Web en.json "checkIn.next.dueNextWeek".
  ///
  /// In en, this message translates to:
  /// **'That\'s after {bills}, which it already counts.'**
  String checkIn_next_dueNextWeek(String bills);

  /// Web en.json "checkIn.next.dueOn".
  ///
  /// In en, this message translates to:
  /// **'{bill} on {date}'**
  String checkIn_next_dueOn(String bill, String date);

  /// Web en.json "checkIn.next.headingFirst".
  ///
  /// In en, this message translates to:
  /// **'Your first weekly figure'**
  String get checkIn_next_headingFirst;

  /// Web en.json "checkIn.next.headingOnTarget".
  ///
  /// In en, this message translates to:
  /// **'Right on target'**
  String get checkIn_next_headingOnTarget;

  /// Web en.json "checkIn.next.headingOver".
  ///
  /// In en, this message translates to:
  /// **'You went {amount} over'**
  String checkIn_next_headingOver(String amount);

  /// Web en.json "checkIn.next.headingUnder".
  ///
  /// In en, this message translates to:
  /// **'You came in {amount} under'**
  String checkIn_next_headingUnder(String amount);

  /// Web en.json "checkIn.next.negative".
  ///
  /// In en, this message translates to:
  /// **'That\'s below zero: this month\'s plan has already spent more than it has.'**
  String get checkIn_next_negative;

  /// Web en.json "checkIn.next.noTarget".
  ///
  /// In en, this message translates to:
  /// **'This is your first check-in, so there was no target to measure this week against.'**
  String get checkIn_next_noTarget;

  /// Web en.json "checkIn.next.perDay".
  ///
  /// In en, this message translates to:
  /// **'≈ {amount}/day'**
  String checkIn_next_perDay(String amount);

  /// Web en.json "checkIn.next.short".
  ///
  /// In en, this message translates to:
  /// **'The month is already short, even though this week came in under, so the only place for it is next week.'**
  String get checkIn_next_short;

  /// Web en.json "checkIn.next.streak".
  ///
  /// In en, this message translates to:
  /// **'{count} weeks under target in a row.'**
  String checkIn_next_streak(String count);

  /// Web en.json "checkIn.next.streakMoved".
  ///
  /// In en, this message translates to:
  /// **'{count} weeks under target in a row — that\'s what moved the debt-free date from {from} to {to}.'**
  String checkIn_next_streakMoved(String count, String from, String to);

  /// Web en.json "checkIn.next.target".
  ///
  /// In en, this message translates to:
  /// **'This week\'s target was {target}, and {spent} of unplanned spending went out.'**
  String checkIn_next_target(String target, String spent);

  /// Web en.json "checkIn.next.where".
  ///
  /// In en, this message translates to:
  /// **'Where should it go?'**
  String get checkIn_next_where;

  /// Web en.json "checkIn.next.youCanSpend".
  ///
  /// In en, this message translates to:
  /// **'Next week you can spend'**
  String get checkIn_next_youCanSpend;

  /// Web en.json "checkIn.nextStep.BILLS".
  ///
  /// In en, this message translates to:
  /// **'Next: bills'**
  String get checkIn_nextStep_BILLS;

  /// Web en.json "checkIn.nextStep.CATEGORIZE".
  ///
  /// In en, this message translates to:
  /// **'Next: categorize'**
  String get checkIn_nextStep_CATEGORIZE;

  /// Web en.json "checkIn.nextStep.NEXT_WEEK".
  ///
  /// In en, this message translates to:
  /// **'Next: next week'**
  String get checkIn_nextStep_NEXT_WEEK;

  /// Web en.json "checkIn.nextStep.WHATS_NEW".
  ///
  /// In en, this message translates to:
  /// **'Next: what\'s new'**
  String get checkIn_nextStep_WHATS_NEW;

  /// Web en.json "checkIn.notStarted".
  ///
  /// In en, this message translates to:
  /// **'Not started yet'**
  String get checkIn_notStarted;

  /// Web en.json "checkIn.notStartedBody".
  ///
  /// In en, this message translates to:
  /// **'This is how the week looks so far. Start the check-in to answer it.'**
  String get checkIn_notStartedBody;

  /// Web en.json "checkIn.notStartedReadOnly".
  ///
  /// In en, this message translates to:
  /// **'Nobody has started this week\'s check-in yet. You can read it, but only an owner or a member can answer it.'**
  String get checkIn_notStartedReadOnly;

  /// Web en.json "checkIn.odd.heading".
  ///
  /// In en, this message translates to:
  /// **'Bigger than usual'**
  String get checkIn_odd_heading;

  /// Web en.json "checkIn.odd.noOffer".
  ///
  /// In en, this message translates to:
  /// **'Budget Owl can\'t tell which bill this is, so there\'s nothing to record. It\'s here so you notice it.'**
  String get checkIn_odd_noOffer;

  /// Web en.json "checkIn.odd.prepaid".
  ///
  /// In en, this message translates to:
  /// **'{multiple} times the {bill} bill at once? Treat it as prepaid and skip the next {skips}.'**
  String checkIn_odd_prepaid(String multiple, String bill, String skips);

  /// Web en.json "checkIn.odd.prepaidOne".
  ///
  /// In en, this message translates to:
  /// **'{multiple} times the {bill} bill at once? Treat it as prepaid and skip the next one.'**
  String checkIn_odd_prepaidOne(String multiple, String bill);

  /// Web en.json "checkIn.odd.usually".
  ///
  /// In en, this message translates to:
  /// **'Usually {usual}'**
  String checkIn_odd_usually(String usual);

  /// Web en.json "checkIn.odd.usuallyBill".
  ///
  /// In en, this message translates to:
  /// **'Your {bill} bill is usually {usual}'**
  String checkIn_odd_usuallyBill(String bill, String usual);

  /// Web en.json "checkIn.odd.yesPrepaid".
  ///
  /// In en, this message translates to:
  /// **'Yes, prepaid'**
  String get checkIn_odd_yesPrepaid;

  /// Web en.json "checkIn.otherCurrencies".
  ///
  /// In en, this message translates to:
  /// **'{count} transactions this week are in accounts in another currency. A check-in looks only at your base currency.'**
  String checkIn_otherCurrencies(String count);

  /// Web en.json "checkIn.overdue.heading".
  ///
  /// In en, this message translates to:
  /// **'Not marked paid'**
  String get checkIn_overdue_heading;

  /// Web en.json "checkIn.overdue.markPaid".
  ///
  /// In en, this message translates to:
  /// **'Mark paid'**
  String get checkIn_overdue_markPaid;

  /// Web en.json "checkIn.overdue.reason".
  ///
  /// In en, this message translates to:
  /// **'{bill} was due {date} ({amount}) and hasn\'t been marked paid.'**
  String checkIn_overdue_reason(String bill, String date, String amount);

  /// Web en.json "checkIn.overdue.skip".
  ///
  /// In en, this message translates to:
  /// **'Skip this month'**
  String get checkIn_overdue_skip;

  /// Web en.json "checkIn.progress".
  ///
  /// In en, this message translates to:
  /// **'{answered} of {shown} answered'**
  String checkIn_progress(String answered, String shown);

  /// Web en.json "checkIn.progressBar".
  ///
  /// In en, this message translates to:
  /// **'Step {current} of {total}'**
  String checkIn_progressBar(String current, String total);

  /// Web en.json "checkIn.prompt.continue".
  ///
  /// In en, this message translates to:
  /// **'Continue'**
  String get checkIn_prompt_continue;

  /// Web en.json "checkIn.prompt.due".
  ///
  /// In en, this message translates to:
  /// **'Due Sunday.'**
  String get checkIn_prompt_due;

  /// Web en.json "checkIn.prompt.label".
  ///
  /// In en, this message translates to:
  /// **'Weekly check-in'**
  String get checkIn_prompt_label;

  /// Web en.json "checkIn.prompt.quiet".
  ///
  /// In en, this message translates to:
  /// **'A few minutes to see where the week went.'**
  String get checkIn_prompt_quiet;

  /// Web en.json "checkIn.prompt.start".
  ///
  /// In en, this message translates to:
  /// **'Start now'**
  String get checkIn_prompt_start;

  /// Web en.json "checkIn.prompt.waiting".
  ///
  /// In en, this message translates to:
  /// **'{count} charges waiting on a category.'**
  String checkIn_prompt_waiting(String count);

  /// Web en.json "checkIn.prompt.waitingOne".
  ///
  /// In en, this message translates to:
  /// **'1 charge waiting on a category.'**
  String get checkIn_prompt_waitingOne;

  /// Web en.json "checkIn.range".
  ///
  /// In en, this message translates to:
  /// **'{from} – {to}'**
  String checkIn_range(String from, String to);

  /// Web en.json "checkIn.readOnly".
  ///
  /// In en, this message translates to:
  /// **'You can read this check-in. Only an owner or a member can answer it.'**
  String get checkIn_readOnly;

  /// Web en.json "checkIn.recordAgainst".
  ///
  /// In en, this message translates to:
  /// **'Also record this against {debt}'**
  String checkIn_recordAgainst(String debt);

  /// Web en.json "checkIn.rules.archived".
  ///
  /// In en, this message translates to:
  /// **'Archived: suggests nothing until you restore the category.'**
  String get checkIn_rules_archived;

  /// Web en.json "checkIn.rules.files".
  ///
  /// In en, this message translates to:
  /// **'files under {category}'**
  String checkIn_rules_files(String category);

  /// Web en.json "checkIn.rules.hide".
  ///
  /// In en, this message translates to:
  /// **'Hide payee rules'**
  String get checkIn_rules_hide;

  /// Web en.json "checkIn.rules.more".
  ///
  /// In en, this message translates to:
  /// **'{count} more rules are not shown here.'**
  String checkIn_rules_more(String count);

  /// Web en.json "checkIn.rules.payeeBody".
  ///
  /// In en, this message translates to:
  /// **'A weekly check-in remembers a payee when you tick \"Always file this payee here\". A rule only suggests: the next check-in arrives with that row already answered, and you can change it.'**
  String get checkIn_rules_payeeBody;

  /// Web en.json "checkIn.rules.payeeEmpty".
  ///
  /// In en, this message translates to:
  /// **'No payee rules yet.'**
  String get checkIn_rules_payeeEmpty;

  /// Web en.json "checkIn.rules.payeeHeading".
  ///
  /// In en, this message translates to:
  /// **'Payee rules'**
  String get checkIn_rules_payeeHeading;

  /// Web en.json "checkIn.rules.remove".
  ///
  /// In en, this message translates to:
  /// **'Remove'**
  String get checkIn_rules_remove;

  /// Web en.json "checkIn.rules.removed".
  ///
  /// In en, this message translates to:
  /// **'The rule was removed.'**
  String get checkIn_rules_removed;

  /// Web en.json "checkIn.rules.show".
  ///
  /// In en, this message translates to:
  /// **'Show payee rules ({count})'**
  String checkIn_rules_show(String count);

  /// Web en.json "checkIn.saving".
  ///
  /// In en, this message translates to:
  /// **'Saving…'**
  String get checkIn_saving;

  /// Web en.json "checkIn.spent".
  ///
  /// In en, this message translates to:
  /// **'Spent {amount} this week'**
  String checkIn_spent(String amount);

  /// Web en.json "checkIn.spentSoFar".
  ///
  /// In en, this message translates to:
  /// **'Spent {amount} so far this week'**
  String checkIn_spentSoFar(String amount);

  /// Web en.json "checkIn.stale.already-recorded".
  ///
  /// In en, this message translates to:
  /// **'This was recorded on Bills & income in the meantime.'**
  String get checkIn_stale_already_recorded;

  /// Web en.json "checkIn.stale.basis-changed".
  ///
  /// In en, this message translates to:
  /// **'The week changed while you were answering: new rows, a rule, a bill or a goal. Look it over and finish again.'**
  String get checkIn_stale_basis_changed;

  /// Web en.json "checkIn.stale.bill-inactive".
  ///
  /// In en, this message translates to:
  /// **'That bill has been retired.'**
  String get checkIn_stale_bill_inactive;

  /// Web en.json "checkIn.stale.debt-paid-off".
  ///
  /// In en, this message translates to:
  /// **'That debt is paid off.'**
  String get checkIn_stale_debt_paid_off;

  /// Web en.json "checkIn.stale.goal-archived".
  ///
  /// In en, this message translates to:
  /// **'That goal has been archived.'**
  String get checkIn_stale_goal_archived;

  /// Web en.json "checkIn.stale.limit-reached".
  ///
  /// In en, this message translates to:
  /// **'That goal or debt has as many records as it can hold.'**
  String get checkIn_stale_limit_reached;

  /// Web en.json "checkIn.stale.subject-changed".
  ///
  /// In en, this message translates to:
  /// **'This changed in the ledger after you answered. Check it and answer again.'**
  String get checkIn_stale_subject_changed;

  /// Web en.json "checkIn.stale.subject-gone".
  ///
  /// In en, this message translates to:
  /// **'This is no longer part of the week.'**
  String get checkIn_stale_subject_gone;

  /// Web en.json "checkIn.stale.surplus-changed".
  ///
  /// In en, this message translates to:
  /// **'This week\'s surplus changed. Choose again.'**
  String get checkIn_stale_surplus_changed;

  /// Web en.json "checkIn.stale.target-archived".
  ///
  /// In en, this message translates to:
  /// **'The category you chose has been archived. Choose another.'**
  String get checkIn_stale_target_archived;

  /// Web en.json "checkIn.stale.transaction-attached-elsewhere".
  ///
  /// In en, this message translates to:
  /// **'This charge is now attached to something else.'**
  String get checkIn_stale_transaction_attached_elsewhere;

  /// Web en.json "checkIn.stale.unknown".
  ///
  /// In en, this message translates to:
  /// **'This needs another look.'**
  String get checkIn_stale_unknown;

  /// Web en.json "checkIn.staleIntro".
  ///
  /// In en, this message translates to:
  /// **'Something changed while you were answering, so nothing was recorded. The marked items need another look.'**
  String get checkIn_staleIntro;

  /// Web en.json "checkIn.start".
  ///
  /// In en, this message translates to:
  /// **'Start check-in'**
  String get checkIn_start;

  /// Web en.json "checkIn.startedBy".
  ///
  /// In en, this message translates to:
  /// **'Started by {name}'**
  String checkIn_startedBy(String name);

  /// Web en.json "checkIn.status.allHandled".
  ///
  /// In en, this message translates to:
  /// **'All handled'**
  String get checkIn_status_allHandled;

  /// Web en.json "checkIn.status.bothHandled".
  ///
  /// In en, this message translates to:
  /// **'Both handled'**
  String get checkIn_status_bothHandled;

  /// Web en.json "checkIn.status.nothingToAnswer".
  ///
  /// In en, this message translates to:
  /// **'Nothing to answer'**
  String get checkIn_status_nothingToAnswer;

  /// Web en.json "checkIn.status.waiting".
  ///
  /// In en, this message translates to:
  /// **'{count} waiting on you'**
  String checkIn_status_waiting(String count);

  /// Web en.json "checkIn.status.waitingOne".
  ///
  /// In en, this message translates to:
  /// **'1 waiting on you'**
  String get checkIn_status_waitingOne;

  /// Web en.json "checkIn.statusChip.COMPLETED".
  ///
  /// In en, this message translates to:
  /// **'Done'**
  String get checkIn_statusChip_COMPLETED;

  /// Web en.json "checkIn.statusChip.DRAFT".
  ///
  /// In en, this message translates to:
  /// **'In progress'**
  String get checkIn_statusChip_DRAFT;

  /// Web en.json "checkIn.statusChip.NOT_STARTED".
  ///
  /// In en, this message translates to:
  /// **'Not started'**
  String get checkIn_statusChip_NOT_STARTED;

  /// Web en.json "checkIn.step.BILLS".
  ///
  /// In en, this message translates to:
  /// **'Bills'**
  String get checkIn_step_BILLS;

  /// Web en.json "checkIn.step.CATEGORIZE".
  ///
  /// In en, this message translates to:
  /// **'Categorize'**
  String get checkIn_step_CATEGORIZE;

  /// Web en.json "checkIn.step.NEXT_WEEK".
  ///
  /// In en, this message translates to:
  /// **'Next week'**
  String get checkIn_step_NEXT_WEEK;

  /// Web en.json "checkIn.step.WHATS_NEW".
  ///
  /// In en, this message translates to:
  /// **'What\'s new'**
  String get checkIn_step_WHATS_NEW;

  /// Web en.json "checkIn.stepNumbered".
  ///
  /// In en, this message translates to:
  /// **'{number} · {name}'**
  String checkIn_stepNumbered(String number, String name);

  /// Web en.json "checkIn.stepsLabel".
  ///
  /// In en, this message translates to:
  /// **'Steps'**
  String get checkIn_stepsLabel;

  /// Web en.json "checkIn.summary.attached".
  ///
  /// In en, this message translates to:
  /// **'Marked {bill} paid with its charge.'**
  String checkIn_summary_attached(String bill);

  /// Web en.json "checkIn.summary.attachedRemembered".
  ///
  /// In en, this message translates to:
  /// **'Marked {bill} paid and remembered the mapping.'**
  String checkIn_summary_attachedRemembered(String bill);

  /// Web en.json "checkIn.summary.byRule".
  ///
  /// In en, this message translates to:
  /// **'{count} of them by your rules.'**
  String checkIn_summary_byRule(String count);

  /// Web en.json "checkIn.summary.debt".
  ///
  /// In en, this message translates to:
  /// **'Recorded a {amount} payment to {debt}.'**
  String checkIn_summary_debt(String amount, String debt);

  /// Web en.json "checkIn.summary.filed".
  ///
  /// In en, this message translates to:
  /// **'Filed {count} charges.'**
  String checkIn_summary_filed(String count);

  /// Web en.json "checkIn.summary.filedOne".
  ///
  /// In en, this message translates to:
  /// **'Filed 1 charge.'**
  String get checkIn_summary_filedOne;

  /// Web en.json "checkIn.summary.goal".
  ///
  /// In en, this message translates to:
  /// **'Recorded {amount} to {goal}.'**
  String checkIn_summary_goal(String amount, String goal);

  /// Web en.json "checkIn.summary.markedPaid".
  ///
  /// In en, this message translates to:
  /// **'Marked {bill} paid.'**
  String checkIn_summary_markedPaid(String bill);

  /// Web en.json "checkIn.summary.nextWeek".
  ///
  /// In en, this message translates to:
  /// **'Next week: {amount}.'**
  String checkIn_summary_nextWeek(String amount);

  /// Web en.json "checkIn.summary.prepaid".
  ///
  /// In en, this message translates to:
  /// **'Marked {bill} prepaid.'**
  String checkIn_summary_prepaid(String bill);

  /// Web en.json "checkIn.summary.remembered".
  ///
  /// In en, this message translates to:
  /// **'Remembered {count} payees.'**
  String checkIn_summary_remembered(String count);

  /// Web en.json "checkIn.summary.rememberedOne".
  ///
  /// In en, this message translates to:
  /// **'Remembered 1 payee.'**
  String get checkIn_summary_rememberedOne;

  /// Web en.json "checkIn.summary.rolledOver".
  ///
  /// In en, this message translates to:
  /// **'Rolled {amount} into next week.'**
  String checkIn_summary_rolledOver(String amount);

  /// Web en.json "checkIn.summary.skipped".
  ///
  /// In en, this message translates to:
  /// **'Skipped {bill} this month.'**
  String checkIn_summary_skipped(String bill);

  /// Web en.json "checkIn.title".
  ///
  /// In en, this message translates to:
  /// **'Check-in for week {week}'**
  String checkIn_title(String week);

  /// Web en.json "checkIn.weekInOneLine".
  ///
  /// In en, this message translates to:
  /// **'Week in one line'**
  String get checkIn_weekInOneLine;

  /// Web en.json "checkIn.whatsNew.heading".
  ///
  /// In en, this message translates to:
  /// **'{count} things to look at'**
  String checkIn_whatsNew_heading(String count);

  /// Web en.json "checkIn.whatsNew.headingOne".
  ///
  /// In en, this message translates to:
  /// **'One thing to look at'**
  String get checkIn_whatsNew_headingOne;

  /// Web en.json "checkIn.whatsNew.headingQuiet".
  ///
  /// In en, this message translates to:
  /// **'Nothing odd this week'**
  String get checkIn_whatsNew_headingQuiet;

  /// Web en.json "checkIn.whatsNew.quiet".
  ///
  /// In en, this message translates to:
  /// **'Quiet week otherwise. Nothing else looks out of the ordinary.'**
  String get checkIn_whatsNew_quiet;

  /// Web en.json "checkIn.yourRule".
  ///
  /// In en, this message translates to:
  /// **'Your rule'**
  String get checkIn_yourRule;

  /// Web en.json "common.cancel".
  ///
  /// In en, this message translates to:
  /// **'Cancel'**
  String get common_cancel;

  /// Web en.json "common.close".
  ///
  /// In en, this message translates to:
  /// **'Close'**
  String get common_close;

  /// Web en.json "common.delete".
  ///
  /// In en, this message translates to:
  /// **'Delete'**
  String get common_delete;

  /// Web en.json "common.done".
  ///
  /// In en, this message translates to:
  /// **'Done'**
  String get common_done;

  /// Web en.json "common.edit".
  ///
  /// In en, this message translates to:
  /// **'Edit'**
  String get common_edit;

  /// Web en.json "common.followDefault".
  ///
  /// In en, this message translates to:
  /// **'Follow the default'**
  String get common_followDefault;

  /// Web en.json "common.loading".
  ///
  /// In en, this message translates to:
  /// **'Loading…'**
  String get common_loading;

  /// Web en.json "common.next".
  ///
  /// In en, this message translates to:
  /// **'Next'**
  String get common_next;

  /// Web en.json "common.previous".
  ///
  /// In en, this message translates to:
  /// **'Previous'**
  String get common_previous;

  /// Web en.json "common.retry".
  ///
  /// In en, this message translates to:
  /// **'Try again'**
  String get common_retry;

  /// Web en.json "common.save".
  ///
  /// In en, this message translates to:
  /// **'Save'**
  String get common_save;

  /// Web en.json "common.saving".
  ///
  /// In en, this message translates to:
  /// **'Saving…'**
  String get common_saving;

  /// Web en.json "common.you".
  ///
  /// In en, this message translates to:
  /// **'You'**
  String get common_you;

  /// Mobile only (l10n/mobile.en.json "connect.address").
  ///
  /// In en, this message translates to:
  /// **'Server address'**
  String get connect_address;

  /// Mobile only (l10n/mobile.en.json "connect.addressHint").
  ///
  /// In en, this message translates to:
  /// **'It starts with https:// — ask whoever set the server up if you are not sure.'**
  String get connect_addressHint;

  /// Mobile only (l10n/mobile.en.json "connect.changeServer").
  ///
  /// In en, this message translates to:
  /// **'Use a different server'**
  String get connect_changeServer;

  /// Mobile only (l10n/mobile.en.json "connect.checking").
  ///
  /// In en, this message translates to:
  /// **'Checking the server…'**
  String get connect_checking;

  /// Mobile only (l10n/mobile.en.json "connect.connectedLede").
  ///
  /// In en, this message translates to:
  /// **'This is a Budget Owl server. What would you like to do?'**
  String get connect_connectedLede;

  /// Mobile only (l10n/mobile.en.json "connect.connectedTo").
  ///
  /// In en, this message translates to:
  /// **'Connected to {host}'**
  String connect_connectedTo(String host);

  /// Mobile only (l10n/mobile.en.json "connect.continue").
  ///
  /// In en, this message translates to:
  /// **'Connect'**
  String get connect_continue;

  /// Mobile only (l10n/mobile.en.json "connect.error.busy").
  ///
  /// In en, this message translates to:
  /// **'The server is busy. Try again in a minute.'**
  String get connect_error_busy;

  /// Mobile only (l10n/mobile.en.json "connect.error.certificateUntrusted").
  ///
  /// In en, this message translates to:
  /// **'This phone doesn\'t trust the server\'s certificate, so Budget Owl sends nothing to it. If your server uses its own certificate authority, install that authority on this phone, then try again.'**
  String get connect_error_certificateUntrusted;

  /// Mobile only (l10n/mobile.en.json "connect.error.certificateWrongName").
  ///
  /// In en, this message translates to:
  /// **'The server\'s certificate is for a different name than the one you entered. Use the name on the certificate, or fix the certificate.'**
  String get connect_error_certificateWrongName;

  /// Mobile only (l10n/mobile.en.json "connect.error.hostNotFound").
  ///
  /// In en, this message translates to:
  /// **'No server answers to that name. Check the spelling, and that this phone is on a network that can reach it.'**
  String get connect_error_hostNotFound;

  /// Mobile only (l10n/mobile.en.json "connect.error.notBudgetOwl").
  ///
  /// In en, this message translates to:
  /// **'Something answered at that address, but it isn\'t a Budget Owl server.'**
  String get connect_error_notBudgetOwl;

  /// Mobile only (l10n/mobile.en.json "connect.error.transport").
  ///
  /// In en, this message translates to:
  /// **'The connection to the server failed part-way. Try again.'**
  String get connect_error_transport;

  /// Mobile only (l10n/mobile.en.json "connect.error.unreachable").
  ///
  /// In en, this message translates to:
  /// **'The server didn\'t answer. It may be switched off, or not reachable from the network this phone is on.'**
  String get connect_error_unreachable;

  /// Mobile only (l10n/mobile.en.json "connect.error.versionMismatch").
  ///
  /// In en, this message translates to:
  /// **'This server\'s API is version {server}, and this app needs version {app}. Update whichever is older, then try again.'**
  String connect_error_versionMismatch(String server, String app);

  /// Mobile only (l10n/mobile.en.json "connect.join").
  ///
  /// In en, this message translates to:
  /// **'I have an invitation link'**
  String get connect_join;

  /// Mobile only (l10n/mobile.en.json "connect.lede").
  ///
  /// In en, this message translates to:
  /// **'Budget Owl runs on your household\'s own server, and this app talks only to it. Enter the address you open Budget Owl at in a browser.'**
  String get connect_lede;

  /// Mobile only (l10n/mobile.en.json "connect.problem.cleartext").
  ///
  /// In en, this message translates to:
  /// **'That address starts with http://, so your password and balances would cross the network unencrypted. Budget Owl connects only over HTTPS.'**
  String get connect_problem_cleartext;

  /// Mobile only (l10n/mobile.en.json "connect.problem.cleartextHelp").
  ///
  /// In en, this message translates to:
  /// **'The user guide\'s page “Give your server an HTTPS address” shows three ways to do it.'**
  String get connect_problem_cleartextHelp;

  /// Mobile only (l10n/mobile.en.json "connect.problem.credentials").
  ///
  /// In en, this message translates to:
  /// **'Take the user name and password out of the address. You sign in on the next screen.'**
  String get connect_problem_credentials;

  /// Mobile only (l10n/mobile.en.json "connect.problem.empty").
  ///
  /// In en, this message translates to:
  /// **'Enter your server\'s address.'**
  String get connect_problem_empty;

  /// Mobile only (l10n/mobile.en.json "connect.problem.invalid").
  ///
  /// In en, this message translates to:
  /// **'That doesn\'t look like a server address.'**
  String get connect_problem_invalid;

  /// Mobile only (l10n/mobile.en.json "connect.problem.path").
  ///
  /// In en, this message translates to:
  /// **'Enter just the server\'s address, with nothing after its name — no /api and no other path.'**
  String get connect_problem_path;

  /// Mobile only (l10n/mobile.en.json "connect.problem.query").
  ///
  /// In en, this message translates to:
  /// **'Enter just the server\'s address, without a ? or # part.'**
  String get connect_problem_query;

  /// Mobile only (l10n/mobile.en.json "connect.setUp").
  ///
  /// In en, this message translates to:
  /// **'Set up a new server'**
  String get connect_setUp;

  /// Mobile only (l10n/mobile.en.json "connect.setUpHint").
  ///
  /// In en, this message translates to:
  /// **'Nobody has an account on this server yet. The first account you create becomes its owner.'**
  String get connect_setUpHint;

  /// Mobile only (l10n/mobile.en.json "connect.signIn").
  ///
  /// In en, this message translates to:
  /// **'Sign in with my account'**
  String get connect_signIn;

  /// Mobile only (l10n/mobile.en.json "connect.title").
  ///
  /// In en, this message translates to:
  /// **'Connect to your server'**
  String get connect_title;

  /// Web en.json "debt.add".
  ///
  /// In en, this message translates to:
  /// **'Add a debt'**
  String get debt_add;

  /// Web en.json "debt.addTitle".
  ///
  /// In en, this message translates to:
  /// **'Add a debt'**
  String get debt_addTitle;

  /// Web en.json "debt.amountSaved".
  ///
  /// In en, this message translates to:
  /// **'Monthly amount saved.'**
  String get debt_amountSaved;

  /// Web en.json "debt.arrangeHint".
  ///
  /// In en, this message translates to:
  /// **'Move a debt up or down to set the order yourself; the dates are worked out again each time.'**
  String get debt_arrangeHint;

  /// Web en.json "debt.balance.confirmedLine".
  ///
  /// In en, this message translates to:
  /// **'{source}. You last confirmed {amount} on {date}.'**
  String debt_balance_confirmedLine(String source, String amount, String date);

  /// Web en.json "debt.balance.estimated".
  ///
  /// In en, this message translates to:
  /// **'An estimate — {months} months assumed since you last confirmed it'**
  String debt_balance_estimated(String months);

  /// Web en.json "debt.balance.estimatedLabel".
  ///
  /// In en, this message translates to:
  /// **'estimated — not confirmed'**
  String get debt_balance_estimatedLabel;

  /// Web en.json "debt.balance.estimatedLine".
  ///
  /// In en, this message translates to:
  /// **'An estimate: {months} months rolled forward from the {amount} you confirmed on {date}, assuming the minimum was paid and the interest in between was added.'**
  String debt_balance_estimatedLine(String months, String amount, String date);

  /// Web en.json "debt.balance.owedNow".
  ///
  /// In en, this message translates to:
  /// **'owed now'**
  String get debt_balance_owedNow;

  /// Web en.json "debt.balance.stale".
  ///
  /// In en, this message translates to:
  /// **'Estimated for over a year. Check a statement — last confirmed {date}.'**
  String debt_balance_stale(String date);

  /// Web en.json "debt.balance.staleLabel".
  ///
  /// In en, this message translates to:
  /// **'last confirmed over a year ago'**
  String get debt_balance_staleLabel;

  /// Web en.json "debt.balance.staleLine".
  ///
  /// In en, this message translates to:
  /// **'Estimated for over a year, so this is the {amount} you confirmed on {date} rather than a year of guesses. A statement figure would put it right.'**
  String debt_balance_staleLine(String amount, String date);

  /// Web en.json "debt.balanceConfirmed".
  ///
  /// In en, this message translates to:
  /// **'Balance confirmed. Anything the arithmetic had wrong is recorded as a correction rather than tidied away.'**
  String get debt_balanceConfirmed;

  /// Web en.json "debt.balanceSource.CONFIRMED".
  ///
  /// In en, this message translates to:
  /// **'Confirmed {date}'**
  String debt_balanceSource_CONFIRMED(String date);

  /// Web en.json "debt.balanceSource.ESTIMATED".
  ///
  /// In en, this message translates to:
  /// **'An estimate, from {date}'**
  String debt_balanceSource_ESTIMATED(String date);

  /// Web en.json "debt.balanceSource.LINKED_STATEMENT".
  ///
  /// In en, this message translates to:
  /// **'From a statement on {date}'**
  String debt_balanceSource_LINKED_STATEMENT(String date);

  /// Web en.json "debt.balanceSource.unknown".
  ///
  /// In en, this message translates to:
  /// **'Balance as at {date}'**
  String debt_balanceSource_unknown(String date);

  /// Web en.json "debt.belowMinimums".
  ///
  /// In en, this message translates to:
  /// **'{amount} a month is less than the {minimums} your debts ask for as minimums.'**
  String debt_belowMinimums(String amount, String minimums);

  /// Web en.json "debt.belowMinimumsNote".
  ///
  /// In en, this message translates to:
  /// **'There is no order to build from that, so no dates are shown. Nothing here is a judgement — the figures are yours and this page will work the moment the monthly amount covers the minimums.'**
  String get debt_belowMinimumsNote;

  /// Web en.json "debt.card.debtFree".
  ///
  /// In en, this message translates to:
  /// **'Debt-free {month}'**
  String debt_card_debtFree(String month);

  /// Web en.json "debt.card.firstWin".
  ///
  /// In en, this message translates to:
  /// **'First one gone {month}'**
  String debt_card_firstWin(String month);

  /// Web en.json "debt.card.noDate".
  ///
  /// In en, this message translates to:
  /// **'No debt-free date, because {reason}'**
  String debt_card_noDate(String reason);

  /// Web en.json "debt.card.noInterest".
  ///
  /// In en, this message translates to:
  /// **'No interest total, because this order has no end date yet.'**
  String get debt_card_noInterest;

  /// Web en.json "debt.card.totalInterest".
  ///
  /// In en, this message translates to:
  /// **'{amount} of interest along the way'**
  String debt_card_totalInterest(String amount);

  /// Web en.json "debt.changeMonthlyAmount".
  ///
  /// In en, this message translates to:
  /// **'Change the monthly amount'**
  String get debt_changeMonthlyAmount;

  /// Web en.json "debt.chart.assumes".
  ///
  /// In en, this message translates to:
  /// **'Every line assumes {amount} a month.'**
  String debt_chart_assumes(String amount);

  /// Web en.json "debt.chart.atMinimum".
  ///
  /// In en, this message translates to:
  /// **'At the minimum — the comparison'**
  String get debt_chart_atMinimum;

  /// Web en.json "debt.chart.atMinimumNote".
  ///
  /// In en, this message translates to:
  /// **'The comparison line assumes the minimum never changes. A card\'s real minimum falls as the balance falls, which stretches it out further still — so the comparison is kinder to the minimum than reality is.'**
  String get debt_chart_atMinimumNote;

  /// Web en.json "debt.chart.buying".
  ///
  /// In en, this message translates to:
  /// **'Paying {extra} extra is buying you {months} months: gone by {soon} instead of {lateValue}.'**
  String debt_chart_buying(String extra, String months, String soon, String lateValue);

  /// Web en.json "debt.chart.clearsMarker".
  ///
  /// In en, this message translates to:
  /// **'Gone by {month}'**
  String debt_chart_clearsMarker(String month);

  /// Web en.json "debt.chart.coincide".
  ///
  /// In en, this message translates to:
  /// **'One line here stands for all of these orders: {lines}. Over this plan they stay closer together than the chart can draw. The cards below give each one\'s own figures.'**
  String debt_chart_coincide(String lines);

  /// Web en.json "debt.chart.detailAssumes".
  ///
  /// In en, this message translates to:
  /// **'The first line assumes {amount} a month.'**
  String debt_chart_detailAssumes(String amount);

  /// Web en.json "debt.chart.marker".
  ///
  /// In en, this message translates to:
  /// **'Debt-free {month}'**
  String debt_chart_marker(String month);

  /// Web en.json "debt.chart.noLine".
  ///
  /// In en, this message translates to:
  /// **'There is no line to draw, because {reason}.'**
  String debt_chart_noLine(String reason);

  /// Web en.json "debt.chart.pace".
  ///
  /// In en, this message translates to:
  /// **'What you are paying'**
  String get debt_chart_pace;

  /// Web en.json "debt.chart.said".
  ///
  /// In en, this message translates to:
  /// **'{name}: {from} in {fromMonth}, down to {to} in {toMonth}.'**
  String debt_chart_said(String name, String from, String fromMonth, String to, String toMonth);

  /// Web en.json "debt.chart.saidNothing".
  ///
  /// In en, this message translates to:
  /// **'{name}: no line, because {reason}.'**
  String debt_chart_saidNothing(String name, String reason);

  /// Web en.json "debt.chart.stride".
  ///
  /// In en, this message translates to:
  /// **'Each point stands for {months} months.'**
  String debt_chart_stride(String months);

  /// Web en.json "debt.chartHeading".
  ///
  /// In en, this message translates to:
  /// **'Balance to zero'**
  String get debt_chartHeading;

  /// Web en.json "debt.clears.never".
  ///
  /// In en, this message translates to:
  /// **'Does not clear — the payment is under the interest'**
  String get debt_clears_never;

  /// Web en.json "debt.clears.noPlan".
  ///
  /// In en, this message translates to:
  /// **'No date — the monthly amount is under the minimums'**
  String get debt_clears_noPlan;

  /// Web en.json "debt.clears.on".
  ///
  /// In en, this message translates to:
  /// **'Clears {month}'**
  String debt_clears_on(String month);

  /// Web en.json "debt.clears.unknown".
  ///
  /// In en, this message translates to:
  /// **'No date yet'**
  String get debt_clears_unknown;

  /// Web en.json "debt.col.actions".
  ///
  /// In en, this message translates to:
  /// **'Actions'**
  String get debt_col_actions;

  /// Web en.json "debt.col.balance".
  ///
  /// In en, this message translates to:
  /// **'Balance'**
  String get debt_col_balance;

  /// Web en.json "debt.col.clears".
  ///
  /// In en, this message translates to:
  /// **'Clears'**
  String get debt_col_clears;

  /// Web en.json "debt.col.debt".
  ///
  /// In en, this message translates to:
  /// **'Debt'**
  String get debt_col_debt;

  /// Web en.json "debt.col.paying".
  ///
  /// In en, this message translates to:
  /// **'Paying'**
  String get debt_col_paying;

  /// Web en.json "debt.col.progress".
  ///
  /// In en, this message translates to:
  /// **'Progress'**
  String get debt_col_progress;

  /// Web en.json "debt.col.rate".
  ///
  /// In en, this message translates to:
  /// **'Rate'**
  String get debt_col_rate;

  /// Web en.json "debt.col.rung".
  ///
  /// In en, this message translates to:
  /// **'Rung'**
  String get debt_col_rung;

  /// Web en.json "debt.compareHeading".
  ///
  /// In en, this message translates to:
  /// **'Avalanche or snowball'**
  String get debt_compareHeading;

  /// Web en.json "debt.comparison.both".
  ///
  /// In en, this message translates to:
  /// **'{cheaper} costs less in interest. {sooner} clears your first debt {months} months sooner. Either is a good answer.'**
  String debt_comparison_both(String cheaper, String sooner, String months);

  /// Web en.json "debt.comparison.cheaperOnly".
  ///
  /// In en, this message translates to:
  /// **'{cheaper} costs less in interest, and both clear your first debt in the same month. Either is a good answer.'**
  String debt_comparison_cheaperOnly(String cheaper);

  /// Web en.json "debt.comparison.same".
  ///
  /// In en, this message translates to:
  /// **'Both orders cost the same and finish together, so take whichever you will stick to.'**
  String get debt_comparison_same;

  /// Web en.json "debt.created".
  ///
  /// In en, this message translates to:
  /// **'Debt added.'**
  String get debt_created;

  /// Web en.json "debt.detail.back".
  ///
  /// In en, this message translates to:
  /// **'Back to the debt plan'**
  String get debt_detail_back;

  /// Web en.json "debt.detail.balanceHeading".
  ///
  /// In en, this message translates to:
  /// **'Balance now'**
  String get debt_detail_balanceHeading;

  /// Web en.json "debt.detail.confirmBalance".
  ///
  /// In en, this message translates to:
  /// **'Confirm the balance'**
  String get debt_detail_confirmBalance;

  /// Web en.json "debt.detail.logPayment".
  ///
  /// In en, this message translates to:
  /// **'Log a payment'**
  String get debt_detail_logPayment;

  /// Web en.json "debt.detail.next".
  ///
  /// In en, this message translates to:
  /// **'Next: {name}'**
  String debt_detail_next(String name);

  /// Web en.json "debt.detail.overTime".
  ///
  /// In en, this message translates to:
  /// **'Balance over time'**
  String get debt_detail_overTime;

  /// Web en.json "debt.detail.previous".
  ///
  /// In en, this message translates to:
  /// **'Previous: {name}'**
  String debt_detail_previous(String name);

  /// Web en.json "debt.detail.switcher".
  ///
  /// In en, this message translates to:
  /// **'Your debts'**
  String get debt_detail_switcher;

  /// Web en.json "debt.editTerms".
  ///
  /// In en, this message translates to:
  /// **'Edit terms'**
  String get debt_editTerms;

  /// Web en.json "debt.editTitle".
  ///
  /// In en, this message translates to:
  /// **'Edit terms'**
  String get debt_editTitle;

  /// Web en.json "debt.empty.body".
  ///
  /// In en, this message translates to:
  /// **'A debt is a balance you are reducing, with terms. Add one and Budget Owl ranks it against the others, works out when each is gone, and shows you what the two usual orders of attack would cost you.'**
  String get debt_empty_body;

  /// Web en.json "debt.empty.counted".
  ///
  /// In en, this message translates to:
  /// **'If a debt\'s minimum is already a bill, say which bill — it is then counted once, not twice.'**
  String get debt_empty_counted;

  /// Web en.json "debt.empty.heading".
  ///
  /// In en, this message translates to:
  /// **'Nothing owed here yet'**
  String get debt_empty_heading;

  /// Web en.json "debt.emptyReadOnly".
  ///
  /// In en, this message translates to:
  /// **'You can see the plan, and only an owner or a member can add a debt.'**
  String get debt_emptyReadOnly;

  /// Web en.json "debt.estimate.assumed".
  ///
  /// In en, this message translates to:
  /// **'That is an estimate. I have assumed {months} months of {minimum} payments since you last confirmed it, and added the interest in between.'**
  String debt_estimate_assumed(String months, String minimum);

  /// Web en.json "debt.estimate.balance".
  ///
  /// In en, this message translates to:
  /// **'What the statement says'**
  String get debt_estimate_balance;

  /// Web en.json "debt.estimate.balanceDate".
  ///
  /// In en, this message translates to:
  /// **'The date it was true'**
  String get debt_estimate_balanceDate;

  /// Web en.json "debt.estimate.balanceHint".
  ///
  /// In en, this message translates to:
  /// **'Digits and a dot, like 8900.00.'**
  String get debt_estimate_balanceHint;

  /// Web en.json "debt.estimate.confirmAmount".
  ///
  /// In en, this message translates to:
  /// **'Confirm {amount}'**
  String debt_estimate_confirmAmount(String amount);

  /// Web en.json "debt.estimate.correct".
  ///
  /// In en, this message translates to:
  /// **'Correct it'**
  String get debt_estimate_correct;

  /// Web en.json "debt.estimate.correctionNote".
  ///
  /// In en, this message translates to:
  /// **'If your figure disagrees with my arithmetic, the difference is recorded as a correction in the history. Nothing already recorded is quietly changed.'**
  String get debt_estimate_correctionNote;

  /// Web en.json "debt.estimate.figureEstimate".
  ///
  /// In en, this message translates to:
  /// **'my estimate of what is owed now'**
  String get debt_estimate_figureEstimate;

  /// Web en.json "debt.estimate.figureStale".
  ///
  /// In en, this message translates to:
  /// **'the last figure you confirmed'**
  String get debt_estimate_figureStale;

  /// Web en.json "debt.estimate.lastConfirmed".
  ///
  /// In en, this message translates to:
  /// **'Last confirmed: {amount} on {date}.'**
  String debt_estimate_lastConfirmed(String amount, String date);

  /// Web en.json "debt.estimate.logInstead".
  ///
  /// In en, this message translates to:
  /// **'Log a payment instead'**
  String get debt_estimate_logInstead;

  /// Web en.json "debt.estimate.save".
  ///
  /// In en, this message translates to:
  /// **'Save the balance'**
  String get debt_estimate_save;

  /// Web en.json "debt.estimate.staleBody".
  ///
  /// In en, this message translates to:
  /// **'It has been {months} months with nothing confirmed, so rather than carry a year of guesses forward this shows the {amount} you last told me.'**
  String debt_estimate_staleBody(String months, String amount);

  /// Web en.json "debt.estimate.staleTitle".
  ///
  /// In en, this message translates to:
  /// **'Time for a statement'**
  String get debt_estimate_staleTitle;

  /// Web en.json "debt.estimate.stopEstimating".
  ///
  /// In en, this message translates to:
  /// **'Stop estimating — use the last figure I confirmed'**
  String get debt_estimate_stopEstimating;

  /// Web en.json "debt.estimate.title".
  ///
  /// In en, this message translates to:
  /// **'Is this still right?'**
  String get debt_estimate_title;

  /// Web en.json "debt.extraExceedsLeft".
  ///
  /// In en, this message translates to:
  /// **'This plan\'s extra of {extra} a month is more than the {left} left this month after your bills, envelopes and goals. Nothing changes automatically.'**
  String debt_extraExceedsLeft(String extra, String left);

  /// Web en.json "debt.field.account".
  ///
  /// In en, this message translates to:
  /// **'Held at'**
  String get debt_field_account;

  /// Web en.json "debt.field.accountHint".
  ///
  /// In en, this message translates to:
  /// **'Optional. A debt you track by hand is ordinary — nothing here needs a bank.'**
  String get debt_field_accountHint;

  /// Web en.json "debt.field.apr".
  ///
  /// In en, this message translates to:
  /// **'Annual rate'**
  String get debt_field_apr;

  /// Web en.json "debt.field.aprAlreadySolved".
  ///
  /// In en, this message translates to:
  /// **'This rate is already worked out from your other figures. Untick to enter one of your own.'**
  String get debt_field_aprAlreadySolved;

  /// Web en.json "debt.field.aprHint".
  ///
  /// In en, this message translates to:
  /// **'The percentage the statement prints — like 22.9. Zero is a real promotional rate and is accepted.'**
  String get debt_field_aprHint;

  /// Web en.json "debt.field.aprSolved".
  ///
  /// In en, this message translates to:
  /// **'Work the rate out for me'**
  String get debt_field_aprSolved;

  /// Web en.json "debt.field.aprSolvedHint".
  ///
  /// In en, this message translates to:
  /// **'Hands the rate back to Budget Owl, which works it out from your other figures and marks it Solved. Your own figure is removed.'**
  String get debt_field_aprSolvedHint;

  /// Web en.json "debt.field.balance".
  ///
  /// In en, this message translates to:
  /// **'Balance now'**
  String get debt_field_balance;

  /// Web en.json "debt.field.balanceDate".
  ///
  /// In en, this message translates to:
  /// **'The date that balance was true'**
  String get debt_field_balanceDate;

  /// Web en.json "debt.field.balanceDateHint".
  ///
  /// In en, this message translates to:
  /// **'Off the statement. The balance and its date travel together, so that later figures can say where they came from.'**
  String get debt_field_balanceDateHint;

  /// Web en.json "debt.field.balanceHint".
  ///
  /// In en, this message translates to:
  /// **'In {currency}, digits and a dot — like 6180.00.'**
  String debt_field_balanceHint(String currency);

  /// Web en.json "debt.field.bill".
  ///
  /// In en, this message translates to:
  /// **'The bill that pays its minimum'**
  String get debt_field_bill;

  /// Web en.json "debt.field.billHint".
  ///
  /// In en, this message translates to:
  /// **'Say which bill and the minimum is counted once. Budget Owl never guesses this from a name or an amount — the figure it decides is what you have left this month.'**
  String get debt_field_billHint;

  /// Web en.json "debt.field.currency".
  ///
  /// In en, this message translates to:
  /// **'Currency'**
  String get debt_field_currency;

  /// Web en.json "debt.field.currencyDefault".
  ///
  /// In en, this message translates to:
  /// **'Your household\'s ({currency})'**
  String debt_field_currencyDefault(String currency);

  /// Web en.json "debt.field.currencyHint".
  ///
  /// In en, this message translates to:
  /// **'Totals are worked out per currency, never converted.'**
  String get debt_field_currencyHint;

  /// Web en.json "debt.field.kind".
  ///
  /// In en, this message translates to:
  /// **'Kind'**
  String get debt_field_kind;

  /// Web en.json "debt.field.kindHint".
  ///
  /// In en, this message translates to:
  /// **'A card revolves; an installment loan has a term.'**
  String get debt_field_kindHint;

  /// Web en.json "debt.field.minimum".
  ///
  /// In en, this message translates to:
  /// **'Minimum payment'**
  String get debt_field_minimum;

  /// Web en.json "debt.field.minimumHint".
  ///
  /// In en, this message translates to:
  /// **'What the lender asks for each month. It never changes this debt\'s own forecast — it is the floor your plan allocates.'**
  String get debt_field_minimumHint;

  /// Web en.json "debt.field.name".
  ///
  /// In en, this message translates to:
  /// **'What you call it'**
  String get debt_field_name;

  /// Web en.json "debt.field.nameHint".
  ///
  /// In en, this message translates to:
  /// **'Whatever you would say out loud — “Visa”, “Car loan”.'**
  String get debt_field_nameHint;

  /// Web en.json "debt.field.noAccount".
  ///
  /// In en, this message translates to:
  /// **'Not an account I track'**
  String get debt_field_noAccount;

  /// Web en.json "debt.field.noBill".
  ///
  /// In en, this message translates to:
  /// **'No bill covers it'**
  String get debt_field_noBill;

  /// Web en.json "debt.field.originalBalance".
  ///
  /// In en, this message translates to:
  /// **'What it started at'**
  String get debt_field_originalBalance;

  /// Web en.json "debt.field.originalBalanceHint".
  ///
  /// In en, this message translates to:
  /// **'Optional, and only used to show progress. Left blank, no progress figure is shown rather than a misleading 0%.'**
  String get debt_field_originalBalanceHint;

  /// Web en.json "debt.field.servicer".
  ///
  /// In en, this message translates to:
  /// **'Who you owe it to'**
  String get debt_field_servicer;

  /// Web en.json "debt.field.servicerHint".
  ///
  /// In en, this message translates to:
  /// **'Optional. The lender or servicer on the statement.'**
  String get debt_field_servicerHint;

  /// Web en.json "debt.footer".
  ///
  /// In en, this message translates to:
  /// **'Minimums · each counted once in Bills & income, so a debt whose minimum is already a bill row is not asked for twice — {minimums}'**
  String debt_footer(String minimums);

  /// Web en.json "debt.header".
  ///
  /// In en, this message translates to:
  /// **'{committed} a month committed · {strategic} of it strategic'**
  String debt_header(String committed, String strategic);

  /// Web en.json "debt.headline".
  ///
  /// In en, this message translates to:
  /// **'{owed} to clear · debt-free {month} on this plan'**
  String debt_headline(String owed, String month);

  /// Web en.json "debt.headline.noDate".
  ///
  /// In en, this message translates to:
  /// **'{owed} to clear · no debt-free date yet, because {reason}'**
  String debt_headline_noDate(String owed, String reason);

  /// Web en.json "debt.history.add".
  ///
  /// In en, this message translates to:
  /// **'Add a past payment'**
  String get debt_history_add;

  /// Web en.json "debt.history.after".
  ///
  /// In en, this message translates to:
  /// **'Balance after {amount}'**
  String debt_history_after(String amount);

  /// Web en.json "debt.history.balanceAfter".
  ///
  /// In en, this message translates to:
  /// **'Balance after'**
  String get debt_history_balanceAfter;

  /// Web en.json "debt.history.caption".
  ///
  /// In en, this message translates to:
  /// **'Every payment recorded against {name}, with how each row got here.'**
  String debt_history_caption(String name);

  /// Web en.json "debt.history.compounding".
  ///
  /// In en, this message translates to:
  /// **'Interest is the balance times the annual rate, divided by twelve, until a statement says otherwise — then the statement wins and the gap is recorded as a correction. It is a simplification you can check with a calculator, and it is not what your lender will charge to the penny.'**
  String get debt_history_compounding;

  /// Web en.json "debt.history.counts".
  ///
  /// In en, this message translates to:
  /// **'{logged} you logged · {corrections} from a statement'**
  String debt_history_counts(String logged, String corrections);

  /// Web en.json "debt.history.date".
  ///
  /// In en, this message translates to:
  /// **'Date'**
  String get debt_history_date;

  /// Web en.json "debt.history.empty".
  ///
  /// In en, this message translates to:
  /// **'Nothing recorded against this one yet. The balance is the figure you confirmed, carried forward.'**
  String get debt_history_empty;

  /// Web en.json "debt.history.heading".
  ///
  /// In en, this message translates to:
  /// **'Payment history'**
  String get debt_history_heading;

  /// Web en.json "debt.history.howItGotHere".
  ///
  /// In en, this message translates to:
  /// **'How it got here'**
  String get debt_history_howItGotHere;

  /// Web en.json "debt.history.interest".
  ///
  /// In en, this message translates to:
  /// **'Interest'**
  String get debt_history_interest;

  /// Web en.json "debt.history.paid".
  ///
  /// In en, this message translates to:
  /// **'Paid'**
  String get debt_history_paid;

  /// Web en.json "debt.history.paidFrom".
  ///
  /// In en, this message translates to:
  /// **'Paid from {account}'**
  String debt_history_paidFrom(String account);

  /// Web en.json "debt.history.principal".
  ///
  /// In en, this message translates to:
  /// **'Principal'**
  String get debt_history_principal;

  /// Web en.json "debt.history.showAll".
  ///
  /// In en, this message translates to:
  /// **'Showing the most recent {count}. The rest are a page away.'**
  String debt_history_showAll(String count);

  /// Web en.json "debt.history.split".
  ///
  /// In en, this message translates to:
  /// **'{interest} interest · {principal} principal'**
  String debt_history_split(String interest, String principal);

  /// Web en.json "debt.inCurrency".
  ///
  /// In en, this message translates to:
  /// **'In {currency}.'**
  String debt_inCurrency(String currency);

  /// Web en.json "debt.kind.CREDIT_CARD".
  ///
  /// In en, this message translates to:
  /// **'Credit card'**
  String get debt_kind_CREDIT_CARD;

  /// Web en.json "debt.kind.INSTALLMENT".
  ///
  /// In en, this message translates to:
  /// **'Installment'**
  String get debt_kind_INSTALLMENT;

  /// Web en.json "debt.kind.unknown".
  ///
  /// In en, this message translates to:
  /// **'Debt'**
  String get debt_kind_unknown;

  /// Web en.json "debt.lede".
  ///
  /// In en, this message translates to:
  /// **'Your balances, one order of attack. Minimums come from your bills; the extra comes from what is left after your goals.'**
  String get debt_lede;

  /// Web en.json "debt.listHeading".
  ///
  /// In en, this message translates to:
  /// **'Your debts'**
  String get debt_listHeading;

  /// Web en.json "debt.mixedCurrency".
  ///
  /// In en, this message translates to:
  /// **'Your debts are in more than one currency, so there is no single debt-free date and no combined interest total. Each currency is worked out on its own — converting would need a rate this instance may not have.'**
  String get debt_mixedCurrency;

  /// Web en.json "debt.monthlyAmount".
  ///
  /// In en, this message translates to:
  /// **'Towards debt each month'**
  String get debt_monthlyAmount;

  /// Web en.json "debt.monthlyAmountHint".
  ///
  /// In en, this message translates to:
  /// **'What you put against your debts in all, minimums included. Every date on this page is worked out from it.'**
  String get debt_monthlyAmountHint;

  /// Web en.json "debt.moveDown".
  ///
  /// In en, this message translates to:
  /// **'Move down'**
  String get debt_moveDown;

  /// Web en.json "debt.moveDownNamed".
  ///
  /// In en, this message translates to:
  /// **'Move down: {name}'**
  String debt_moveDownNamed(String name);

  /// Web en.json "debt.moveUp".
  ///
  /// In en, this message translates to:
  /// **'Move up'**
  String get debt_moveUp;

  /// Web en.json "debt.moveUpNamed".
  ///
  /// In en, this message translates to:
  /// **'Move up: {name}'**
  String debt_moveUpNamed(String name);

  /// Web en.json "debt.movedTo".
  ///
  /// In en, this message translates to:
  /// **'{name} is now rung {rung} of {ofValue}.'**
  String debt_movedTo(String name, String rung, String ofValue);

  /// Web en.json "debt.naming".
  ///
  /// In en, this message translates to:
  /// **'{kind} · {servicer}'**
  String debt_naming(String kind, String servicer);

  /// Web en.json "debt.noProgress".
  ///
  /// In en, this message translates to:
  /// **'No starting balance recorded'**
  String get debt_noProgress;

  /// Web en.json "debt.open".
  ///
  /// In en, this message translates to:
  /// **'Open'**
  String get debt_open;

  /// Web en.json "debt.openAny".
  ///
  /// In en, this message translates to:
  /// **'Open any debt for its terms and payments.'**
  String get debt_openAny;

  /// Web en.json "debt.order".
  ///
  /// In en, this message translates to:
  /// **'{strategy} order.'**
  String debt_order(String strategy);

  /// Web en.json "debt.orderCustom".
  ///
  /// In en, this message translates to:
  /// **'In the order you chose.'**
  String get debt_orderCustom;

  /// Web en.json "debt.paying.clearsIt".
  ///
  /// In en, this message translates to:
  /// **'{amount} — all that is left to clear it'**
  String debt_paying_clearsIt(String amount);

  /// Web en.json "debt.paying.minimumOnly".
  ///
  /// In en, this message translates to:
  /// **'{amount} minimum'**
  String debt_paying_minimumOnly(String amount);

  /// Web en.json "debt.paying.minimumPlusExtra".
  ///
  /// In en, this message translates to:
  /// **'{minimum} minimum + {extra} extra'**
  String debt_paying_minimumPlusExtra(String minimum, String extra);

  /// Web en.json "debt.paying.minimumUntil".
  ///
  /// In en, this message translates to:
  /// **'Minimum {amount} a month until the rung above clears'**
  String debt_paying_minimumUntil(String amount);

  /// Web en.json "debt.paying.none".
  ///
  /// In en, this message translates to:
  /// **'{amount} minimum'**
  String debt_paying_none(String amount);

  /// Web en.json "debt.payment.amount".
  ///
  /// In en, this message translates to:
  /// **'Amount paid'**
  String get debt_payment_amount;

  /// Web en.json "debt.payment.amountHint".
  ///
  /// In en, this message translates to:
  /// **'Digits and a dot, like 445.00.'**
  String get debt_payment_amountHint;

  /// Web en.json "debt.payment.lede".
  ///
  /// In en, this message translates to:
  /// **'Filled in with what your plan puts against this one. Change the amount if you paid something else.'**
  String get debt_payment_lede;

  /// Web en.json "debt.payment.noAccount".
  ///
  /// In en, this message translates to:
  /// **'Somewhere I do not track'**
  String get debt_payment_noAccount;

  /// Web en.json "debt.payment.note".
  ///
  /// In en, this message translates to:
  /// **'Note'**
  String get debt_payment_note;

  /// Web en.json "debt.payment.paidDate".
  ///
  /// In en, this message translates to:
  /// **'Date paid'**
  String get debt_payment_paidDate;

  /// Web en.json "debt.payment.paidFrom".
  ///
  /// In en, this message translates to:
  /// **'Paid from'**
  String get debt_payment_paidFrom;

  /// Web en.json "debt.payment.paidFromHint".
  ///
  /// In en, this message translates to:
  /// **'Which of your accounts the money left. Nothing is recorded against that account and no transaction is created — this is only where a matching charge will be looked for later.'**
  String get debt_payment_paidFromHint;

  /// Web en.json "debt.payment.preview".
  ///
  /// In en, this message translates to:
  /// **'{interest} interest · {principal} principal · balance after {balance}'**
  String debt_payment_preview(String interest, String principal, String balance);

  /// Web en.json "debt.payment.previewUnavailable".
  ///
  /// In en, this message translates to:
  /// **'I could not work out the split just now. You can still record the payment — the interest and the principal will be shown on it once it is.'**
  String get debt_payment_previewUnavailable;

  /// Web en.json "debt.payment.record".
  ///
  /// In en, this message translates to:
  /// **'Record it'**
  String get debt_payment_record;

  /// Web en.json "debt.payment.recorded".
  ///
  /// In en, this message translates to:
  /// **'Payment recorded: {interest} interest · {principal} principal. Balance now {balance}.'**
  String debt_payment_recorded(String interest, String principal, String balance);

  /// Web en.json "debt.payment.splitNote".
  ///
  /// In en, this message translates to:
  /// **'Budget Owl works out how much of this went on interest and how much came off the balance, using the rate in force on the day, and shows you both the moment it is recorded.'**
  String get debt_payment_splitNote;

  /// Web en.json "debt.payment.title".
  ///
  /// In en, this message translates to:
  /// **'Log a payment to {name}'**
  String debt_payment_title(String name);

  /// Web en.json "debt.payment.useMore".
  ///
  /// In en, this message translates to:
  /// **'{amount}'**
  String debt_payment_useMore(String amount);

  /// Web en.json "debt.payment.useScheduled".
  ///
  /// In en, this message translates to:
  /// **'{amount} — what is scheduled'**
  String debt_payment_useScheduled(String amount);

  /// Web en.json "debt.progress".
  ///
  /// In en, this message translates to:
  /// **'{paid} paid off · {percent}% of {original}'**
  String debt_progress(String paid, String percent, String original);

  /// Web en.json "debt.provenance.AUTO_MATCHED".
  ///
  /// In en, this message translates to:
  /// **'Matched automatically'**
  String get debt_provenance_AUTO_MATCHED;

  /// Web en.json "debt.provenance.STATEMENT_CORRECTION".
  ///
  /// In en, this message translates to:
  /// **'Statement correction'**
  String get debt_provenance_STATEMENT_CORRECTION;

  /// Web en.json "debt.provenance.USER_LOGGED".
  ///
  /// In en, this message translates to:
  /// **'You logged it'**
  String get debt_provenance_USER_LOGGED;

  /// Web en.json "debt.provenance.unknown".
  ///
  /// In en, this message translates to:
  /// **'Recorded'**
  String get debt_provenance_unknown;

  /// Web en.json "debt.rate.notEntered".
  ///
  /// In en, this message translates to:
  /// **'Rate not entered'**
  String get debt_rate_notEntered;

  /// Web en.json "debt.rate.unavailable".
  ///
  /// In en, this message translates to:
  /// **'Rate not available'**
  String get debt_rate_unavailable;

  /// Web en.json "debt.rate.value".
  ///
  /// In en, this message translates to:
  /// **'{rate}%'**
  String debt_rate_value(String rate);

  /// Web en.json "debt.rateReleased".
  ///
  /// In en, this message translates to:
  /// **'Terms saved. The rate is now worked out from your other figures and is marked Solved.'**
  String get debt_rateReleased;

  /// Web en.json "debt.rationale.AVALANCHE".
  ///
  /// In en, this message translates to:
  /// **'Highest rate first, so the money goes where it is costing you most.'**
  String get debt_rationale_AVALANCHE;

  /// Web en.json "debt.rationale.SNOWBALL".
  ///
  /// In en, this message translates to:
  /// **'Smallest balance first, so the first debt is gone soonest — which some people need to keep going.'**
  String get debt_rationale_SNOWBALL;

  /// Web en.json "debt.reason.belowMinimums".
  ///
  /// In en, this message translates to:
  /// **'the monthly amount is less than the minimums'**
  String get debt_reason_belowMinimums;

  /// Web en.json "debt.reason.neverClears".
  ///
  /// In en, this message translates to:
  /// **'the payment is less than the interest this balance builds up, so it grows'**
  String get debt_reason_neverClears;

  /// Web en.json "debt.reason.unknown".
  ///
  /// In en, this message translates to:
  /// **'there is no projection for it'**
  String get debt_reason_unknown;

  /// Web en.json "debt.rung".
  ///
  /// In en, this message translates to:
  /// **'Rung {rung}'**
  String debt_rung(String rung);

  /// Web en.json "debt.rungNamed".
  ///
  /// In en, this message translates to:
  /// **'{rung} {name}'**
  String debt_rungNamed(String rung, String name);

  /// Web en.json "debt.selected".
  ///
  /// In en, this message translates to:
  /// **'In use'**
  String get debt_selected;

  /// Web en.json "debt.settledHeading".
  ///
  /// In en, this message translates to:
  /// **'Not in the ladder'**
  String get debt_settledHeading;

  /// Web en.json "debt.source.ENTERED".
  ///
  /// In en, this message translates to:
  /// **'You entered this'**
  String get debt_source_ENTERED;

  /// Web en.json "debt.source.FROM_PLAN".
  ///
  /// In en, this message translates to:
  /// **'From your plan'**
  String get debt_source_FROM_PLAN;

  /// Web en.json "debt.source.SOLVED".
  ///
  /// In en, this message translates to:
  /// **'Solved'**
  String get debt_source_SOLVED;

  /// Web en.json "debt.source.unknown".
  ///
  /// In en, this message translates to:
  /// **'Source not known'**
  String get debt_source_unknown;

  /// Web en.json "debt.strategy.AVALANCHE".
  ///
  /// In en, this message translates to:
  /// **'Avalanche'**
  String get debt_strategy_AVALANCHE;

  /// Web en.json "debt.strategy.CUSTOM".
  ///
  /// In en, this message translates to:
  /// **'Your own order'**
  String get debt_strategy_CUSTOM;

  /// Web en.json "debt.strategy.SNOWBALL".
  ///
  /// In en, this message translates to:
  /// **'Snowball'**
  String get debt_strategy_SNOWBALL;

  /// Web en.json "debt.strategy.unknown".
  ///
  /// In en, this message translates to:
  /// **'Your plan'**
  String get debt_strategy_unknown;

  /// Web en.json "debt.strategyHeading".
  ///
  /// In en, this message translates to:
  /// **'Order of attack'**
  String get debt_strategyHeading;

  /// Web en.json "debt.strategyIs".
  ///
  /// In en, this message translates to:
  /// **'Your plan uses {name}.'**
  String debt_strategyIs(String name);

  /// Web en.json "debt.strategyNote".
  ///
  /// In en, this message translates to:
  /// **'Changing this changes every date on this page. Both orders are worked out either way, so you can compare before you choose.'**
  String get debt_strategyNote;

  /// Web en.json "debt.strategySaved".
  ///
  /// In en, this message translates to:
  /// **'Order of attack saved.'**
  String get debt_strategySaved;

  /// Web en.json "debt.tableCaption".
  ///
  /// In en, this message translates to:
  /// **'Your debts in {strategy} order, with what each one is paying and when it clears.'**
  String debt_tableCaption(String strategy);

  /// Web en.json "debt.tableCaptionCustom".
  ///
  /// In en, this message translates to:
  /// **'Your debts in the order you chose, with what each one is paying and when it clears.'**
  String get debt_tableCaptionCustom;

  /// Web en.json "debt.term.apr".
  ///
  /// In en, this message translates to:
  /// **'Annual rate'**
  String get debt_term_apr;

  /// Web en.json "debt.term.balance".
  ///
  /// In en, this message translates to:
  /// **'Balance now'**
  String get debt_term_balance;

  /// Web en.json "debt.term.minimum".
  ///
  /// In en, this message translates to:
  /// **'Minimum payment'**
  String get debt_term_minimum;

  /// Web en.json "debt.term.monthsLeft".
  ///
  /// In en, this message translates to:
  /// **'Months left'**
  String get debt_term_monthsLeft;

  /// Web en.json "debt.term.neverClears".
  ///
  /// In en, this message translates to:
  /// **'Never, at this payment'**
  String get debt_term_neverClears;

  /// Web en.json "debt.term.noInterestTotal".
  ///
  /// In en, this message translates to:
  /// **'No total — it never clears'**
  String get debt_term_noInterestTotal;

  /// Web en.json "debt.term.noOriginal".
  ///
  /// In en, this message translates to:
  /// **'Not recorded'**
  String get debt_term_noOriginal;

  /// Web en.json "debt.term.noPayment".
  ///
  /// In en, this message translates to:
  /// **'Not set'**
  String get debt_term_noPayment;

  /// Web en.json "debt.term.noValue".
  ///
  /// In en, this message translates to:
  /// **'No answer yet'**
  String get debt_term_noValue;

  /// Web en.json "debt.term.originalBalance".
  ///
  /// In en, this message translates to:
  /// **'What it started at'**
  String get debt_term_originalBalance;

  /// Web en.json "debt.term.payment".
  ///
  /// In en, this message translates to:
  /// **'What you are paying'**
  String get debt_term_payment;

  /// Web en.json "debt.term.payoffMonth".
  ///
  /// In en, this message translates to:
  /// **'Gone by'**
  String get debt_term_payoffMonth;

  /// Web en.json "debt.term.totalInterest".
  ///
  /// In en, this message translates to:
  /// **'Interest still to come'**
  String get debt_term_totalInterest;

  /// Web en.json "debt.terms.balanceNeverSolved".
  ///
  /// In en, this message translates to:
  /// **'The balance is never worked out from the other figures. It is the one number you can read off a statement, so it only ever changes when you say so.'**
  String get debt_terms_balanceNeverSolved;

  /// Web en.json "debt.terms.count".
  ///
  /// In en, this message translates to:
  /// **'{entered} entered · {solved} solved.'**
  String debt_terms_count(String entered, String solved);

  /// Web en.json "debt.terms.heading".
  ///
  /// In en, this message translates to:
  /// **'Loan terms'**
  String get debt_terms_heading;

  /// Web en.json "debt.terms.promise".
  ///
  /// In en, this message translates to:
  /// **'Give me any three of the rate, the payment and the horizon and I will work out the fourth. Anything marked Solved is my arithmetic, not your word.'**
  String get debt_terms_promise;

  /// Web en.json "debt.title".
  ///
  /// In en, this message translates to:
  /// **'Debt plan'**
  String get debt_title;

  /// Web en.json "debt.updated".
  ///
  /// In en, this message translates to:
  /// **'Terms saved.'**
  String get debt_updated;

  /// Web en.json "debt.useAvalanche".
  ///
  /// In en, this message translates to:
  /// **'Use avalanche'**
  String get debt_useAvalanche;

  /// Web en.json "debt.whatIf.months".
  ///
  /// In en, this message translates to:
  /// **'Adding {step} a month would make you debt-free {month} — {months} months sooner.'**
  String debt_whatIf_months(String step, String month, String months);

  /// Web en.json "debt.whatIf.noChange".
  ///
  /// In en, this message translates to:
  /// **'Adding {step} a month would not move the date: {month} either way.'**
  String debt_whatIf_noChange(String step, String month);

  /// Web en.json "debt.whatIf.oneMonth".
  ///
  /// In en, this message translates to:
  /// **'Adding {step} a month would make you debt-free {month} — a month sooner.'**
  String debt_whatIf_oneMonth(String step, String month);

  /// Web en.json "debt.yourOrder".
  ///
  /// In en, this message translates to:
  /// **'Your own order: debt-free {month}, with {amount} of interest along the way.'**
  String debt_yourOrder(String month, String amount);

  /// Web en.json "debt.yourOrder.noDate".
  ///
  /// In en, this message translates to:
  /// **'Your own order has no debt-free date, because {reason}.'**
  String debt_yourOrder_noDate(String reason);

  /// Mobile only (l10n/mobile.en.json "deviceLabel.android").
  ///
  /// In en, this message translates to:
  /// **'Phone · Android'**
  String get deviceLabel_android;

  /// Mobile only (l10n/mobile.en.json "deviceLabel.ios").
  ///
  /// In en, this message translates to:
  /// **'iPhone · iOS'**
  String get deviceLabel_ios;

  /// Web en.json "devices.browserOn".
  ///
  /// In en, this message translates to:
  /// **'{browser} on {system}'**
  String devices_browserOn(String browser, String system);

  /// Web en.json "devices.confirmHeading".
  ///
  /// In en, this message translates to:
  /// **'Sign out of this browser?'**
  String get devices_confirmHeading;

  /// Mobile only (l10n/mobile.en.json "devices.confirmHeadingPhone").
  ///
  /// In en, this message translates to:
  /// **'Sign out of this phone?'**
  String get devices_confirmHeadingPhone;

  /// Web en.json "devices.confirmMessage".
  ///
  /// In en, this message translates to:
  /// **'This is the browser you are using now. You will be signed out and taken to the sign-in screen.'**
  String get devices_confirmMessage;

  /// Mobile only (l10n/mobile.en.json "devices.confirmMessagePhone").
  ///
  /// In en, this message translates to:
  /// **'This is the phone you are using now. You will be signed out, and what Budget Owl keeps on it is erased.'**
  String get devices_confirmMessagePhone;

  /// Web en.json "devices.empty".
  ///
  /// In en, this message translates to:
  /// **'Nothing is signed in.'**
  String get devices_empty;

  /// Web en.json "devices.heading".
  ///
  /// In en, this message translates to:
  /// **'Where you are signed in'**
  String get devices_heading;

  /// Web en.json "devices.kindApp".
  ///
  /// In en, this message translates to:
  /// **'Mobile app'**
  String get devices_kindApp;

  /// Web en.json "devices.kindSession".
  ///
  /// In en, this message translates to:
  /// **'Web browser'**
  String get devices_kindSession;

  /// Web en.json "devices.lastUsed".
  ///
  /// In en, this message translates to:
  /// **'Last used {date}'**
  String devices_lastUsed(String date);

  /// Web en.json "devices.lede".
  ///
  /// In en, this message translates to:
  /// **'Every browser and phone where you are signed in. Sign one out and it stops working immediately.'**
  String get devices_lede;

  /// Web en.json "devices.revoked".
  ///
  /// In en, this message translates to:
  /// **'That device has been signed out.'**
  String get devices_revoked;

  /// Web en.json "devices.signOut".
  ///
  /// In en, this message translates to:
  /// **'Sign out'**
  String get devices_signOut;

  /// Web en.json "devices.signOutHere".
  ///
  /// In en, this message translates to:
  /// **'Sign out here'**
  String get devices_signOutHere;

  /// Web en.json "devices.signedInAt".
  ///
  /// In en, this message translates to:
  /// **'Signed in {date}'**
  String devices_signedInAt(String date);

  /// Web en.json "devices.thisDevice".
  ///
  /// In en, this message translates to:
  /// **'This device'**
  String get devices_thisDevice;

  /// Mobile only (l10n/mobile.en.json "devices.thisPhone").
  ///
  /// In en, this message translates to:
  /// **'This phone'**
  String get devices_thisPhone;

  /// Web en.json "devices.title".
  ///
  /// In en, this message translates to:
  /// **'Devices'**
  String get devices_title;

  /// Web en.json "duration.hour".
  ///
  /// In en, this message translates to:
  /// **'1 hour'**
  String get duration_hour;

  /// Web en.json "duration.hours".
  ///
  /// In en, this message translates to:
  /// **'{count} hours'**
  String duration_hours(String count);

  /// Web en.json "duration.minute".
  ///
  /// In en, this message translates to:
  /// **'1 minute'**
  String get duration_minute;

  /// Web en.json "duration.minutes".
  ///
  /// In en, this message translates to:
  /// **'{count} minutes'**
  String duration_minutes(String count);

  /// Web en.json "duration.seconds".
  ///
  /// In en, this message translates to:
  /// **'{count} seconds'**
  String duration_seconds(String count);

  /// Web en.json "envelopes.add".
  ///
  /// In en, this message translates to:
  /// **'Add an envelope'**
  String get envelopes_add;

  /// Web en.json "envelopes.addFirst".
  ///
  /// In en, this message translates to:
  /// **'Add your first envelope'**
  String get envelopes_addFirst;

  /// Web en.json "envelopes.addTitle".
  ///
  /// In en, this message translates to:
  /// **'Add an envelope'**
  String get envelopes_addTitle;

  /// Web en.json "envelopes.amount".
  ///
  /// In en, this message translates to:
  /// **'Amount'**
  String get envelopes_amount;

  /// Web en.json "envelopes.amountHint".
  ///
  /// In en, this message translates to:
  /// **'In {currency}.'**
  String envelopes_amountHint(String currency);

  /// Web en.json "envelopes.archive".
  ///
  /// In en, this message translates to:
  /// **'Archive'**
  String get envelopes_archive;

  /// Web en.json "envelopes.archiveHeading".
  ///
  /// In en, this message translates to:
  /// **'Archive {name}?'**
  String envelopes_archiveHeading(String name);

  /// Web en.json "envelopes.archiveMessage".
  ///
  /// In en, this message translates to:
  /// **'It stops taking new entries and drops out of this month\'s list. Everything you\'ve logged against it is kept. You can restore it at any time.'**
  String get envelopes_archiveMessage;

  /// Web en.json "envelopes.archived".
  ///
  /// In en, this message translates to:
  /// **'Envelope archived.'**
  String get envelopes_archived;

  /// Web en.json "envelopes.archivedHeading".
  ///
  /// In en, this message translates to:
  /// **'Archived envelopes'**
  String get envelopes_archivedHeading;

  /// Web en.json "envelopes.archivedNote".
  ///
  /// In en, this message translates to:
  /// **'These don\'t take new entries, but what you logged against them is still here.'**
  String get envelopes_archivedNote;

  /// Web en.json "envelopes.category".
  ///
  /// In en, this message translates to:
  /// **'Category'**
  String get envelopes_category;

  /// Web en.json "envelopes.created".
  ///
  /// In en, this message translates to:
  /// **'Envelope added.'**
  String get envelopes_created;

  /// Web en.json "envelopes.date".
  ///
  /// In en, this message translates to:
  /// **'Date'**
  String get envelopes_date;

  /// Web en.json "envelopes.deleteEntryHeading".
  ///
  /// In en, this message translates to:
  /// **'Remove this entry?'**
  String get envelopes_deleteEntryHeading;

  /// Web en.json "envelopes.deleteEntryMessage".
  ///
  /// In en, this message translates to:
  /// **'The amount goes back to {name}. If it came from a transaction, the transaction itself is kept.'**
  String envelopes_deleteEntryMessage(String name);

  /// Web en.json "envelopes.deleteHeading".
  ///
  /// In en, this message translates to:
  /// **'Delete {name}?'**
  String envelopes_deleteHeading(String name);

  /// Web en.json "envelopes.deleteMessage".
  ///
  /// In en, this message translates to:
  /// **'Its {count} logged entries go with it. Your transactions are kept — only the envelope\'s own record of them is removed. This can\'t be undone.'**
  String envelopes_deleteMessage(String count);

  /// Web en.json "envelopes.deleted".
  ///
  /// In en, this message translates to:
  /// **'Envelope deleted.'**
  String get envelopes_deleted;

  /// Web en.json "envelopes.editEntryTitle".
  ///
  /// In en, this message translates to:
  /// **'Edit this entry'**
  String get envelopes_editEntryTitle;

  /// Web en.json "envelopes.editTitle".
  ///
  /// In en, this message translates to:
  /// **'Edit envelope'**
  String get envelopes_editTitle;

  /// Web en.json "envelopes.emptyBody".
  ///
  /// In en, this message translates to:
  /// **'An envelope is an amount you decide on and spend down — groceries, eating out, fuel. Set one up and you\'ll see what\'s left as you go.'**
  String get envelopes_emptyBody;

  /// Web en.json "envelopes.emptyReadOnly".
  ///
  /// In en, this message translates to:
  /// **'Ask an owner or member of your household to set some up.'**
  String get envelopes_emptyReadOnly;

  /// Web en.json "envelopes.entryDeleted".
  ///
  /// In en, this message translates to:
  /// **'Entry removed.'**
  String get envelopes_entryDeleted;

  /// Web en.json "envelopes.entryUpdated".
  ///
  /// In en, this message translates to:
  /// **'Entry saved.'**
  String get envelopes_entryUpdated;

  /// Web en.json "envelopes.heading".
  ///
  /// In en, this message translates to:
  /// **'Your envelopes'**
  String get envelopes_heading;

  /// Web en.json "envelopes.hideArchived".
  ///
  /// In en, this message translates to:
  /// **'Hide {count} archived'**
  String envelopes_hideArchived(String count);

  /// Web en.json "envelopes.hideLog".
  ///
  /// In en, this message translates to:
  /// **'Hide entries'**
  String get envelopes_hideLog;

  /// Web en.json "envelopes.lede".
  ///
  /// In en, this message translates to:
  /// **'Money you\'ve set aside for the spending that changes week to week. Log what you spend and see what\'s left, straight away.'**
  String get envelopes_lede;

  /// Web en.json "envelopes.left".
  ///
  /// In en, this message translates to:
  /// **'left'**
  String get envelopes_left;

  /// Web en.json "envelopes.logEmpty".
  ///
  /// In en, this message translates to:
  /// **'Nothing logged against this envelope yet.'**
  String get envelopes_logEmpty;

  /// Web en.json "envelopes.logIt".
  ///
  /// In en, this message translates to:
  /// **'Log it'**
  String get envelopes_logIt;

  /// Web en.json "envelopes.logSpend".
  ///
  /// In en, this message translates to:
  /// **'Log spend'**
  String get envelopes_logSpend;

  /// Web en.json "envelopes.logTitle".
  ///
  /// In en, this message translates to:
  /// **'Log spend in {envelope}'**
  String envelopes_logTitle(String envelope);

  /// Web en.json "envelopes.logged".
  ///
  /// In en, this message translates to:
  /// **'Logged.'**
  String get envelopes_logged;

  /// Web en.json "envelopes.meterLabel".
  ///
  /// In en, this message translates to:
  /// **'{name}: {percent}% of the amount spent'**
  String envelopes_meterLabel(String name, String percent);

  /// Web en.json "envelopes.month".
  ///
  /// In en, this message translates to:
  /// **'Month'**
  String get envelopes_month;

  /// Web en.json "envelopes.monthFixedHint".
  ///
  /// In en, this message translates to:
  /// **'An envelope stays in the month it was set for. Make a new one for another month.'**
  String get envelopes_monthFixedHint;

  /// Web en.json "envelopes.name".
  ///
  /// In en, this message translates to:
  /// **'Name'**
  String get envelopes_name;

  /// Web en.json "envelopes.nameHint".
  ///
  /// In en, this message translates to:
  /// **'Keep them narrow — “Groceries” and “Eating out” are two envelopes, not one.'**
  String get envelopes_nameHint;

  /// Web en.json "envelopes.nextMonth".
  ///
  /// In en, this message translates to:
  /// **'Next month'**
  String get envelopes_nextMonth;

  /// Web en.json "envelopes.noCategory".
  ///
  /// In en, this message translates to:
  /// **'No category'**
  String get envelopes_noCategory;

  /// Web en.json "envelopes.note".
  ///
  /// In en, this message translates to:
  /// **'Note'**
  String get envelopes_note;

  /// Web en.json "envelopes.optional".
  ///
  /// In en, this message translates to:
  /// **'Optional.'**
  String get envelopes_optional;

  /// Web en.json "envelopes.over".
  ///
  /// In en, this message translates to:
  /// **'over'**
  String get envelopes_over;

  /// Web en.json "envelopes.payee".
  ///
  /// In en, this message translates to:
  /// **'Who it was with'**
  String get envelopes_payee;

  /// Web en.json "envelopes.periodLabel".
  ///
  /// In en, this message translates to:
  /// **'Choose a month'**
  String get envelopes_periodLabel;

  /// Web en.json "envelopes.previousMonth".
  ///
  /// In en, this message translates to:
  /// **'Previous month'**
  String get envelopes_previousMonth;

  /// Web en.json "envelopes.runningTotal".
  ///
  /// In en, this message translates to:
  /// **'Spent so far'**
  String get envelopes_runningTotal;

  /// Web en.json "envelopes.showArchived".
  ///
  /// In en, this message translates to:
  /// **'Show {count} archived'**
  String envelopes_showArchived(String count);

  /// Web en.json "envelopes.showLog".
  ///
  /// In en, this message translates to:
  /// **'Show {count} entries'**
  String envelopes_showLog(String count);

  /// Web en.json "envelopes.showLogOne".
  ///
  /// In en, this message translates to:
  /// **'Show 1 entry'**
  String get envelopes_showLogOne;

  /// Web en.json "envelopes.source.MANUAL".
  ///
  /// In en, this message translates to:
  /// **'Typed'**
  String get envelopes_source_MANUAL;

  /// Web en.json "envelopes.source.SYNCED".
  ///
  /// In en, this message translates to:
  /// **'From a transaction'**
  String get envelopes_source_SYNCED;

  /// Web en.json "envelopes.source.unknown".
  ///
  /// In en, this message translates to:
  /// **'Other'**
  String get envelopes_source_unknown;

  /// Web en.json "envelopes.title".
  ///
  /// In en, this message translates to:
  /// **'Envelopes'**
  String get envelopes_title;

  /// Web en.json "envelopes.totalAllocated".
  ///
  /// In en, this message translates to:
  /// **'Set aside'**
  String get envelopes_totalAllocated;

  /// Web en.json "envelopes.totalRemaining".
  ///
  /// In en, this message translates to:
  /// **'Left'**
  String get envelopes_totalRemaining;

  /// Web en.json "envelopes.totalSpent".
  ///
  /// In en, this message translates to:
  /// **'Spent'**
  String get envelopes_totalSpent;

  /// Web en.json "envelopes.unarchive".
  ///
  /// In en, this message translates to:
  /// **'Restore'**
  String get envelopes_unarchive;

  /// Web en.json "envelopes.unarchived".
  ///
  /// In en, this message translates to:
  /// **'Envelope restored.'**
  String get envelopes_unarchived;

  /// Web en.json "envelopes.updated".
  ///
  /// In en, this message translates to:
  /// **'Envelope saved.'**
  String get envelopes_updated;

  /// Web en.json "error.account-archived".
  ///
  /// In en, this message translates to:
  /// **'That account is archived, so nothing new can be recorded against it. Restore it first — what\'s already recorded is kept.'**
  String get error_account_archived;

  /// Web en.json "error.account-currency-immutable".
  ///
  /// In en, this message translates to:
  /// **'This account\'s currency cannot be changed now that it has transactions. The amounts already recorded would mean something different.'**
  String get error_account_currency_immutable;

  /// Web en.json "error.account-not-empty".
  ///
  /// In en, this message translates to:
  /// **'This account has transactions recorded against it, so it cannot be deleted. Archive it instead — it keeps its history and disappears from your lists.'**
  String get error_account_not_empty;

  /// Web en.json "error.authentication-failed".
  ///
  /// In en, this message translates to:
  /// **'The email address or password is not right.'**
  String get error_authentication_failed;

  /// Web en.json "error.bill-inactive".
  ///
  /// In en, this message translates to:
  /// **'This bill is retired, so nothing new can be recorded against it. Restore it first — what was already recorded is kept.'**
  String get error_bill_inactive;

  /// Web en.json "error.bill-name-taken".
  ///
  /// In en, this message translates to:
  /// **'You already have a bill with that name.'**
  String get error_bill_name_taken;

  /// Web en.json "error.charge-rule-limit-reached".
  ///
  /// In en, this message translates to:
  /// **'This household has as many bill mappings as it can hold. Unmap one on Bills & income first.'**
  String get error_charge_rule_limit_reached;

  /// Web en.json "error.check-in-completed".
  ///
  /// In en, this message translates to:
  /// **'This check-in is already done. A finished check-in can\'t be changed.'**
  String get error_check_in_completed;

  /// Web en.json "error.check-in-draft-open".
  ///
  /// In en, this message translates to:
  /// **'There\'s already an unfinished check-in for another week. Finish or discard it first.'**
  String get error_check_in_draft_open;

  /// Web en.json "error.check-in-incomplete".
  ///
  /// In en, this message translates to:
  /// **'Choose where this week\'s surplus goes before you finish.'**
  String get error_check_in_incomplete;

  /// Web en.json "error.check-in-stale".
  ///
  /// In en, this message translates to:
  /// **'Something changed while you were answering, so nothing was recorded. Look the week over and finish again.'**
  String get error_check_in_stale;

  /// Web en.json "error.check-in-week-out-of-range".
  ///
  /// In en, this message translates to:
  /// **'That week is too far back or too far ahead for a check-in.'**
  String get error_check_in_week_out_of_range;

  /// Web en.json "error.conflict".
  ///
  /// In en, this message translates to:
  /// **'That conflicts with a change made elsewhere. Reload the page and try again.'**
  String get error_conflict;

  /// Web en.json "error.csrf-token-required".
  ///
  /// In en, this message translates to:
  /// **'Your session could not be verified. Reload the page and try again.'**
  String get error_csrf_token_required;

  /// Web en.json "error.current-password-incorrect".
  ///
  /// In en, this message translates to:
  /// **'The current password is not right.'**
  String get error_current_password_incorrect;

  /// Web en.json "error.forbidden".
  ///
  /// In en, this message translates to:
  /// **'You do not have permission to do that.'**
  String get error_forbidden;

  /// Web en.json "error.generic".
  ///
  /// In en, this message translates to:
  /// **'Something went wrong on our side. Try again in a moment.'**
  String get error_generic;

  /// Web en.json "error.goal-archived".
  ///
  /// In en, this message translates to:
  /// **'This goal is archived. Restore it to set money aside or change its plan.'**
  String get error_goal_archived;

  /// Web en.json "error.goal-currency-mismatch".
  ///
  /// In en, this message translates to:
  /// **'That account is in a different currency from this goal, so it cannot hold it.'**
  String get error_goal_currency_mismatch;

  /// Web en.json "error.goal-limit-reached".
  ///
  /// In en, this message translates to:
  /// **'This household has as many goals, or this goal as many records, as one household can hold. Archive or delete one first.'**
  String get error_goal_limit_reached;

  /// Web en.json "error.goal-name-taken".
  ///
  /// In en, this message translates to:
  /// **'You already have a goal with this name. Choose a different one.'**
  String get error_goal_name_taken;

  /// Web en.json "error.goal-plan-period-past".
  ///
  /// In en, this message translates to:
  /// **'The plan can only change from this month on. To correct a past month, record or remove what was set aside then.'**
  String get error_goal_plan_period_past;

  /// Web en.json "error.goal-plan-period-too-far".
  ///
  /// In en, this message translates to:
  /// **'The plan can only change up to two years ahead. Choose an earlier month.'**
  String get error_goal_plan_period_too_far;

  /// Web en.json "error.import-content-encoding-refused".
  ///
  /// In en, this message translates to:
  /// **'That upload was compressed on the way, which Budget Owl doesn\'t accept. Reload the page and try again.'**
  String get error_import_content_encoding_refused;

  /// Web en.json "error.import-currency-mismatch".
  ///
  /// In en, this message translates to:
  /// **'That statement is in a different currency from the account you chose.'**
  String get error_import_currency_mismatch;

  /// Web en.json "error.import-encoding-unsupported".
  ///
  /// In en, this message translates to:
  /// **'Budget Owl can\'t read that character set. Choose another one.'**
  String get error_import_encoding_unsupported;

  /// Web en.json "error.import-file-empty".
  ///
  /// In en, this message translates to:
  /// **'That file is empty.'**
  String get error_import_file_empty;

  /// Web en.json "error.import-file-malformed".
  ///
  /// In en, this message translates to:
  /// **'That file can\'t be read as a bank statement.'**
  String get error_import_file_malformed;

  /// Web en.json "error.import-file-malformed.binary".
  ///
  /// In en, this message translates to:
  /// **'That file isn\'t text. Check you chose the statement your bank exported, not a spreadsheet or a PDF.'**
  String get error_import_file_malformed_binary;

  /// Web en.json "error.import-file-malformed.compressed".
  ///
  /// In en, this message translates to:
  /// **'That file is compressed. Unzip it and choose the statement inside.'**
  String get error_import_file_malformed_compressed;

  /// Web en.json "error.import-file-malformed.dtd".
  ///
  /// In en, this message translates to:
  /// **'That file contains a document type declaration. Bank statements don\'t need one, and Budget Owl doesn\'t read them.'**
  String get error_import_file_malformed_dtd;

  /// Web en.json "error.import-file-malformed.element-too-long".
  ///
  /// In en, this message translates to:
  /// **'One value in that file is far longer than a statement\'s.'**
  String get error_import_file_malformed_element_too_long;

  /// Web en.json "error.import-file-malformed.encrypted".
  ///
  /// In en, this message translates to:
  /// **'That file is password-protected. Export it again without a password.'**
  String get error_import_file_malformed_encrypted;

  /// Web en.json "error.import-file-malformed.header".
  ///
  /// In en, this message translates to:
  /// **'The start of that file isn\'t a valid OFX header.'**
  String get error_import_file_malformed_header;

  /// Web en.json "error.import-file-malformed.line-too-long".
  ///
  /// In en, this message translates to:
  /// **'One line in that file is far longer than a statement\'s.'**
  String get error_import_file_malformed_line_too_long;

  /// Web en.json "error.import-file-malformed.not-a-statement".
  ///
  /// In en, this message translates to:
  /// **'That file doesn\'t contain a statement Budget Owl can read.'**
  String get error_import_file_malformed_not_a_statement;

  /// Web en.json "error.import-file-malformed.too-deep".
  ///
  /// In en, this message translates to:
  /// **'That file is nested more deeply than any bank statement.'**
  String get error_import_file_malformed_too_deep;

  /// Web en.json "error.import-file-malformed.too-many-columns".
  ///
  /// In en, this message translates to:
  /// **'That file has more columns than any statement Budget Owl reads.'**
  String get error_import_file_malformed_too_many_columns;

  /// Web en.json "error.import-file-malformed.too-many-elements".
  ///
  /// In en, this message translates to:
  /// **'That file has more parts than any statement Budget Owl reads.'**
  String get error_import_file_malformed_too_many_elements;

  /// Web en.json "error.import-file-malformed.unclosed-quote".
  ///
  /// In en, this message translates to:
  /// **'That file has a quote that\'s never closed, so its columns can\'t be read.'**
  String get error_import_file_malformed_unclosed_quote;

  /// Web en.json "error.import-file-too-complex".
  ///
  /// In en, this message translates to:
  /// **'That file took too long to read. Export a shorter date range and try again.'**
  String get error_import_file_too_complex;

  /// Web en.json "error.import-file-too-large".
  ///
  /// In en, this message translates to:
  /// **'That file is larger than Budget Owl reads at once (5 MB). Export a shorter date range and import it in parts. If the file is smaller than that, the server in front of Budget Owl has a lower limit; ask whoever runs it.'**
  String get error_import_file_too_large;

  /// Web en.json "error.import-format-mismatch".
  ///
  /// In en, this message translates to:
  /// **'That file isn\'t in the format you chose. Check the Format setting.'**
  String get error_import_format_mismatch;

  /// Web en.json "error.import-multiple-accounts".
  ///
  /// In en, this message translates to:
  /// **'That file holds statements for more than one account. Export one account at a time.'**
  String get error_import_multiple_accounts;

  /// Web en.json "error.import-preview-stale".
  ///
  /// In en, this message translates to:
  /// **'Your ledger changed since the preview, so nothing was imported. Preview the file again.'**
  String get error_import_preview_stale;

  /// Web en.json "error.import-too-many-rows".
  ///
  /// In en, this message translates to:
  /// **'That file has more than 10,000 transactions, the most Budget Owl reads at once. Export a shorter date range and import it in parts.'**
  String get error_import_too_many_rows;

  /// Web en.json "error.income-name-taken".
  ///
  /// In en, this message translates to:
  /// **'You already have an income source with that name.'**
  String get error_income_name_taken;

  /// Web en.json "error.internal-error".
  ///
  /// In en, this message translates to:
  /// **'Something went wrong on our side. Try again in a moment.'**
  String get error_internal_error;

  /// Web en.json "error.invitation-refused".
  ///
  /// In en, this message translates to:
  /// **'An invitation could not be created for that email address.'**
  String get error_invitation_refused;

  /// Web en.json "error.invitation-requires-sign-out".
  ///
  /// In en, this message translates to:
  /// **'You are signed in. Sign out before accepting an invitation.'**
  String get error_invitation_requires_sign_out;

  /// Web en.json "error.invitation-unusable".
  ///
  /// In en, this message translates to:
  /// **'This invitation link cannot be used. It may have expired, been used already, or been cancelled. Ask the person who invited you for a new one.'**
  String get error_invitation_unusable;

  /// Web en.json "error.last-owner".
  ///
  /// In en, this message translates to:
  /// **'A household must keep at least one owner. Make someone else an owner first.'**
  String get error_last_owner;

  /// Web en.json "error.malformed-request".
  ///
  /// In en, this message translates to:
  /// **'That request could not be understood. Reload the page and try again.'**
  String get error_malformed_request;

  /// Web en.json "error.network".
  ///
  /// In en, this message translates to:
  /// **'Budget Owl could not be reached. Check that the server is running and that you are connected, then try again.'**
  String get error_network;

  /// Web en.json "error.not-a-member".
  ///
  /// In en, this message translates to:
  /// **'You are not a member of this household.'**
  String get error_not_a_member;

  /// Web en.json "error.not-authenticated".
  ///
  /// In en, this message translates to:
  /// **'You are not signed in. Sign in and try again.'**
  String get error_not_authenticated;

  /// Web en.json "error.not-found".
  ///
  /// In en, this message translates to:
  /// **'That could not be found. It may have been removed.'**
  String get error_not_found;

  /// Web en.json "error.not-instance-admin".
  ///
  /// In en, this message translates to:
  /// **'Only the person who administers this instance can do that. On a self-hosted Budget Owl that is whoever set it up.'**
  String get error_not_instance_admin;

  /// Web en.json "error.own-role-unchangeable".
  ///
  /// In en, this message translates to:
  /// **'Nobody can change their own role. Ask another owner.'**
  String get error_own_role_unchangeable;

  /// Web en.json "error.owner-only".
  ///
  /// In en, this message translates to:
  /// **'Only an owner of the household can do that.'**
  String get error_owner_only;

  /// Web en.json "error.password-login-locked".
  ///
  /// In en, this message translates to:
  /// **'Email and password sign-in cannot be turned off yet. An owner has to complete a sign-in through your provider first, which proves the new way in works before the old one is hidden.'**
  String get error_password_login_locked;

  /// Web en.json "error.password-unacceptable".
  ///
  /// In en, this message translates to:
  /// **'That password cannot be used. Choose a different one.'**
  String get error_password_unacceptable;

  /// Web en.json "error.password-unacceptable.breached".
  ///
  /// In en, this message translates to:
  /// **'That password appears on a list of commonly used passwords. Choose a different one.'**
  String get error_password_unacceptable_breached;

  /// Web en.json "error.password-unacceptable.too-long".
  ///
  /// In en, this message translates to:
  /// **'That password is too long. Use at most {maximumBytes} bytes.'**
  String error_password_unacceptable_too_long(String maximumBytes);

  /// Web en.json "error.password-unacceptable.too-short".
  ///
  /// In en, this message translates to:
  /// **'That password is too short. Use at least {minimumLength} characters.'**
  String error_password_unacceptable_too_short(String minimumLength);

  /// Web en.json "error.payee-rule-limit-reached".
  ///
  /// In en, this message translates to:
  /// **'This household has as many payee rules as it can hold. Remove one on the categories page first.'**
  String get error_payee_rule_limit_reached;

  /// Web en.json "error.rate-limited".
  ///
  /// In en, this message translates to:
  /// **'Too many attempts. Wait {minutes} and try again.'**
  String error_rate_limited(String minutes);

  /// Web en.json "error.read-only-role".
  ///
  /// In en, this message translates to:
  /// **'Your role in this household is read-only.'**
  String get error_read_only_role;

  /// Web en.json "error.reference".
  ///
  /// In en, this message translates to:
  /// **'Reference: {id}'**
  String error_reference(String id);

  /// Web en.json "error.report-range-invalid".
  ///
  /// In en, this message translates to:
  /// **'That range of months doesn\'t work. Choose a start month on or before the end month.'**
  String get error_report_range_invalid;

  /// Web en.json "error.report-range-too-long".
  ///
  /// In en, this message translates to:
  /// **'Insights can show at most {maximumMonths} months at a time.'**
  String error_report_range_too_long(String maximumMonths);

  /// Web en.json "error.setup-already-complete".
  ///
  /// In en, this message translates to:
  /// **'This instance has already been set up. Sign in instead.'**
  String get error_setup_already_complete;

  /// Web en.json "error.stale-version".
  ///
  /// In en, this message translates to:
  /// **'Somebody else changed this while you were editing it. Nothing has been saved.'**
  String get error_stale_version;

  /// Web en.json "error.transfer-leg-in-envelope".
  ///
  /// In en, this message translates to:
  /// **'One of these rows is logged against an envelope. Moving money between your own accounts isn\'t spending, so take it out of the envelope first, then pair the rows.'**
  String get error_transfer_leg_in_envelope;

  /// Web en.json "error.transfer-leg-not-separate".
  ///
  /// In en, this message translates to:
  /// **'This is one half of a transfer, and the two halves are always changed together. Edit or delete the transfer itself.'**
  String get error_transfer_leg_not_separate;

  /// Web en.json "error.transfer-pair-mismatch".
  ///
  /// In en, this message translates to:
  /// **'Those two rows can\'t be one transfer.'**
  String get error_transfer_pair_mismatch;

  /// Web en.json "error.transfer-pair-mismatch.amounts-differ".
  ///
  /// In en, this message translates to:
  /// **'The two amounts aren\'t equal. In one currency, what left one account has to be what arrived in the other.'**
  String get error_transfer_pair_mismatch_amounts_differ;

  /// Web en.json "error.transfer-pair-mismatch.same-account".
  ///
  /// In en, this message translates to:
  /// **'Both rows are in the same account. A transfer moves money between two different accounts.'**
  String get error_transfer_pair_mismatch_same_account;

  /// Web en.json "error.transfer-pair-mismatch.same-sign".
  ///
  /// In en, this message translates to:
  /// **'Both rows went the same way. A transfer needs money leaving one account and arriving in the other.'**
  String get error_transfer_pair_mismatch_same_sign;

  /// Web en.json "error.validation-failed".
  ///
  /// In en, this message translates to:
  /// **'Some of what you entered is not valid. Check the highlighted fields.'**
  String get error_validation_failed;

  /// Web en.json "goals.add".
  ///
  /// In en, this message translates to:
  /// **'Add a goal'**
  String get goals_add;

  /// Web en.json "goals.archive.action".
  ///
  /// In en, this message translates to:
  /// **'Archive'**
  String get goals_archive_action;

  /// Web en.json "goals.archive.body".
  ///
  /// In en, this message translates to:
  /// **'Its history stays, and it stops being part of the month\'s plan. Anything it planned or recorded this month stops coming off what\'s left to spend, so that figure goes up.'**
  String get goals_archive_body;

  /// Web en.json "goals.archive.confirm".
  ///
  /// In en, this message translates to:
  /// **'Archive'**
  String get goals_archive_confirm;

  /// Web en.json "goals.archive.done".
  ///
  /// In en, this message translates to:
  /// **'The goal is archived.'**
  String get goals_archive_done;

  /// Web en.json "goals.archive.heading".
  ///
  /// In en, this message translates to:
  /// **'Archive {name}?'**
  String goals_archive_heading(String name);

  /// Web en.json "goals.archived.body".
  ///
  /// In en, this message translates to:
  /// **'An archived goal keeps its history but is no longer part of the month\'s plan, so nothing it planned or recorded comes off what\'s left to spend.'**
  String get goals_archived_body;

  /// Web en.json "goals.archived.heading".
  ///
  /// In en, this message translates to:
  /// **'Archived goals'**
  String get goals_archived_heading;

  /// Web en.json "goals.archived.hide".
  ///
  /// In en, this message translates to:
  /// **'Hide archived goals'**
  String get goals_archived_hide;

  /// Web en.json "goals.archived.show".
  ///
  /// In en, this message translates to:
  /// **'Show archived goals'**
  String get goals_archived_show;

  /// Web en.json "goals.archivedTag".
  ///
  /// In en, this message translates to:
  /// **'Archived'**
  String get goals_archivedTag;

  /// Web en.json "goals.back".
  ///
  /// In en, this message translates to:
  /// **'← All goals'**
  String get goals_back;

  /// Mobile only (l10n/mobile.en.json "goals.backToAll").
  ///
  /// In en, this message translates to:
  /// **'Back to all goals'**
  String get goals_backToAll;

  /// Web en.json "goals.contribute.amount".
  ///
  /// In en, this message translates to:
  /// **'Amount'**
  String get goals_contribute_amount;

  /// Web en.json "goals.contribute.date".
  ///
  /// In en, this message translates to:
  /// **'Date'**
  String get goals_contribute_date;

  /// Web en.json "goals.contribute.inSave".
  ///
  /// In en, this message translates to:
  /// **'Set aside'**
  String get goals_contribute_inSave;

  /// Web en.json "goals.contribute.inTitle".
  ///
  /// In en, this message translates to:
  /// **'Set money aside for {name}'**
  String goals_contribute_inTitle(String name);

  /// Web en.json "goals.contribute.noLedger".
  ///
  /// In en, this message translates to:
  /// **'This records it against the goal only. Nothing is added to or taken from any account — move the money at your bank as usual.'**
  String get goals_contribute_noLedger;

  /// Web en.json "goals.contribute.note".
  ///
  /// In en, this message translates to:
  /// **'Note (optional)'**
  String get goals_contribute_note;

  /// Web en.json "goals.contribute.outSave".
  ///
  /// In en, this message translates to:
  /// **'Take out'**
  String get goals_contribute_outSave;

  /// Web en.json "goals.contribute.outTitle".
  ///
  /// In en, this message translates to:
  /// **'Take money out of {name}'**
  String goals_contribute_outTitle(String name);

  /// Web en.json "goals.contribute.prefilled".
  ///
  /// In en, this message translates to:
  /// **'Filled in with what is still to come for {month}: {amount}. Change it for a top-up.'**
  String goals_contribute_prefilled(String month, String amount);

  /// Web en.json "goals.contribute.recordedIn".
  ///
  /// In en, this message translates to:
  /// **'Money set aside.'**
  String get goals_contribute_recordedIn;

  /// Web en.json "goals.contribute.recordedOut".
  ///
  /// In en, this message translates to:
  /// **'Money taken out.'**
  String get goals_contribute_recordedOut;

  /// Web en.json "goals.countsTowardsLeft".
  ///
  /// In en, this message translates to:
  /// **'Each goal\'s monthly amount is taken off what\'s left to spend this month, on Bills & income. It counts in full whether or not you have set it aside yet.'**
  String get goals_countsTowardsLeft;

  /// Web en.json "goals.created".
  ///
  /// In en, this message translates to:
  /// **'{name} is added.'**
  String goals_created(String name);

  /// Web en.json "goals.currencyGroup".
  ///
  /// In en, this message translates to:
  /// **'Goals in {currency}'**
  String goals_currencyGroup(String currency);

  /// Web en.json "goals.delete.action".
  ///
  /// In en, this message translates to:
  /// **'Delete'**
  String get goals_delete_action;

  /// Web en.json "goals.delete.body".
  ///
  /// In en, this message translates to:
  /// **'This deletes the goal, its plan and everything recorded against it, permanently. No transaction is touched. To keep the history, archive it instead.'**
  String get goals_delete_body;

  /// Web en.json "goals.delete.confirm".
  ///
  /// In en, this message translates to:
  /// **'Delete permanently'**
  String get goals_delete_confirm;

  /// Web en.json "goals.delete.heading".
  ///
  /// In en, this message translates to:
  /// **'Delete {name} permanently?'**
  String goals_delete_heading(String name);

  /// Web en.json "goals.detail.accountBalance".
  ///
  /// In en, this message translates to:
  /// **'{account} has {amount}. That is the account\'s balance, not this goal\'s.'**
  String goals_detail_accountBalance(String account, String amount);

  /// Web en.json "goals.detail.actions".
  ///
  /// In en, this message translates to:
  /// **'Actions'**
  String get goals_detail_actions;

  /// Web en.json "goals.detail.alreadySaved".
  ///
  /// In en, this message translates to:
  /// **'Already saved when you started tracking it: {amount}'**
  String goals_detail_alreadySaved(String amount);

  /// Web en.json "goals.detail.amount".
  ///
  /// In en, this message translates to:
  /// **'Amount'**
  String get goals_detail_amount;

  /// Web en.json "goals.detail.by".
  ///
  /// In en, this message translates to:
  /// **'By {month}'**
  String goals_detail_by(String month);

  /// Web en.json "goals.detail.date".
  ///
  /// In en, this message translates to:
  /// **'Date'**
  String get goals_detail_date;

  /// Web en.json "goals.detail.exactly".
  ///
  /// In en, this message translates to:
  /// **'Reached, exactly.'**
  String get goals_detail_exactly;

  /// Web en.json "goals.detail.history".
  ///
  /// In en, this message translates to:
  /// **'History'**
  String get goals_detail_history;

  /// Web en.json "goals.detail.historyCaption".
  ///
  /// In en, this message translates to:
  /// **'Money set aside for this goal and taken out of it, newest first.'**
  String get goals_detail_historyCaption;

  /// Web en.json "goals.detail.keptIn".
  ///
  /// In en, this message translates to:
  /// **'Kept in {account}'**
  String goals_detail_keptIn(String account);

  /// Web en.json "goals.detail.monthly".
  ///
  /// In en, this message translates to:
  /// **'{amount} a month in {month}.'**
  String goals_detail_monthly(String amount, String month);

  /// Web en.json "goals.detail.noHistory".
  ///
  /// In en, this message translates to:
  /// **'Nothing recorded yet.'**
  String get goals_detail_noHistory;

  /// Web en.json "goals.detail.noMonth".
  ///
  /// In en, this message translates to:
  /// **'No target month'**
  String get goals_detail_noMonth;

  /// Web en.json "goals.detail.note".
  ///
  /// In en, this message translates to:
  /// **'Note'**
  String get goals_detail_note;

  /// Web en.json "goals.detail.over".
  ///
  /// In en, this message translates to:
  /// **'Reached, with {amount} more than the target.'**
  String goals_detail_over(String amount);

  /// Web en.json "goals.detail.plan".
  ///
  /// In en, this message translates to:
  /// **'The plan'**
  String get goals_detail_plan;

  /// Web en.json "goals.detail.progress".
  ///
  /// In en, this message translates to:
  /// **'How far along'**
  String get goals_detail_progress;

  /// Web en.json "goals.detail.remove".
  ///
  /// In en, this message translates to:
  /// **'Remove'**
  String get goals_detail_remove;

  /// Web en.json "goals.detail.showMore".
  ///
  /// In en, this message translates to:
  /// **'Show more'**
  String get goals_detail_showMore;

  /// Web en.json "goals.detail.step".
  ///
  /// In en, this message translates to:
  /// **'{amount} a month from {month}.'**
  String goals_detail_step(String amount, String month);

  /// Web en.json "goals.detail.thisMonth".
  ///
  /// In en, this message translates to:
  /// **'For {month}: {planned} planned, {recorded} recorded so far.'**
  String goals_detail_thisMonth(String month, String planned, String recorded);

  /// Web en.json "goals.detail.toGo".
  ///
  /// In en, this message translates to:
  /// **'{amount} to go.'**
  String goals_detail_toGo(String amount);

  /// Web en.json "goals.detail.withdrawal".
  ///
  /// In en, this message translates to:
  /// **'Withdrawal'**
  String get goals_detail_withdrawal;

  /// Web en.json "goals.dialog.addTitle".
  ///
  /// In en, this message translates to:
  /// **'Add a goal'**
  String get goals_dialog_addTitle;

  /// Web en.json "goals.dialog.editTitle".
  ///
  /// In en, this message translates to:
  /// **'Edit goal'**
  String get goals_dialog_editTitle;

  /// Web en.json "goals.empty.body".
  ///
  /// In en, this message translates to:
  /// **'A goal is money you put aside for something — an emergency fund, a trip, a deposit. Give it a name and how much it needs, with a month if you have one, and see it fill up.'**
  String get goals_empty_body;

  /// Web en.json "goals.empty.heading".
  ///
  /// In en, this message translates to:
  /// **'Nothing you are saving for yet'**
  String get goals_empty_heading;

  /// Web en.json "goals.emptyReadOnly".
  ///
  /// In en, this message translates to:
  /// **'Your role is read-only, so goals are added by an owner or a member.'**
  String get goals_emptyReadOnly;

  /// Web en.json "goals.field.account".
  ///
  /// In en, this message translates to:
  /// **'Where it is kept (optional)'**
  String get goals_field_account;

  /// Web en.json "goals.field.accountHint".
  ///
  /// In en, this message translates to:
  /// **'For your reference. The goal\'s figure comes from what you record here, because one account often holds several goals.'**
  String get goals_field_accountHint;

  /// Web en.json "goals.field.alreadySaved".
  ///
  /// In en, this message translates to:
  /// **'Already saved (optional)'**
  String get goals_field_alreadySaved;

  /// Web en.json "goals.field.alreadySavedHint".
  ///
  /// In en, this message translates to:
  /// **'Money you had already put by before you started tracking it. It counts towards the goal and never towards any month\'s left-over figure.'**
  String get goals_field_alreadySavedHint;

  /// Web en.json "goals.field.currency".
  ///
  /// In en, this message translates to:
  /// **'Currency'**
  String get goals_field_currency;

  /// Web en.json "goals.field.currencyDefault".
  ///
  /// In en, this message translates to:
  /// **'Your household\'s currency ({currency})'**
  String goals_field_currencyDefault(String currency);

  /// Web en.json "goals.field.currencyHint".
  ///
  /// In en, this message translates to:
  /// **'Fixed once the goal is added. A goal kept in an account is in that account\'s currency.'**
  String get goals_field_currencyHint;

  /// Web en.json "goals.field.month".
  ///
  /// In en, this message translates to:
  /// **'Month you want it by (optional)'**
  String get goals_field_month;

  /// Web en.json "goals.field.monthHint".
  ///
  /// In en, this message translates to:
  /// **'Leave it empty for a goal with no date, like an emergency fund.'**
  String get goals_field_monthHint;

  /// Web en.json "goals.field.monthly".
  ///
  /// In en, this message translates to:
  /// **'Each month (optional)'**
  String get goals_field_monthly;

  /// Web en.json "goals.field.monthlyHint".
  ///
  /// In en, this message translates to:
  /// **'What you plan to set aside every month, from this month. It comes off what\'s left to spend.'**
  String get goals_field_monthlyHint;

  /// Web en.json "goals.field.name".
  ///
  /// In en, this message translates to:
  /// **'What it is for'**
  String get goals_field_name;

  /// Web en.json "goals.field.noAccount".
  ///
  /// In en, this message translates to:
  /// **'Nowhere in particular'**
  String get goals_field_noAccount;

  /// Web en.json "goals.field.target".
  ///
  /// In en, this message translates to:
  /// **'How much it needs'**
  String get goals_field_target;

  /// Web en.json "goals.lede".
  ///
  /// In en, this message translates to:
  /// **'Money you are putting aside for something. What you plan to set aside each month comes off what\'s left to spend.'**
  String get goals_lede;

  /// Web en.json "goals.listLabel".
  ///
  /// In en, this message translates to:
  /// **'Your goals'**
  String get goals_listLabel;

  /// Web en.json "goals.noInterest".
  ///
  /// In en, this message translates to:
  /// **'This assumes no interest or growth: a goal is what you put in.'**
  String get goals_noInterest;

  /// Web en.json "goals.open".
  ///
  /// In en, this message translates to:
  /// **'Open →'**
  String get goals_open;

  /// Mobile only (l10n/mobile.en.json "goals.openNamed").
  ///
  /// In en, this message translates to:
  /// **'Open {name}'**
  String goals_openNamed(String name);

  /// Mobile only (l10n/mobile.en.json "goals.openTrail").
  ///
  /// In en, this message translates to:
  /// **'Open ›'**
  String get goals_openTrail;

  /// Web en.json "goals.overdueTag".
  ///
  /// In en, this message translates to:
  /// **'Overdue'**
  String get goals_overdueTag;

  /// Web en.json "goals.plan.amount".
  ///
  /// In en, this message translates to:
  /// **'Each month'**
  String get goals_plan_amount;

  /// Web en.json "goals.plan.amountHint".
  ///
  /// In en, this message translates to:
  /// **'Zero pauses the goal.'**
  String get goals_plan_amountHint;

  /// Web en.json "goals.plan.change".
  ///
  /// In en, this message translates to:
  /// **'Change'**
  String get goals_plan_change;

  /// Web en.json "goals.plan.from".
  ///
  /// In en, this message translates to:
  /// **'Starting'**
  String get goals_plan_from;

  /// Web en.json "goals.plan.fromNext".
  ///
  /// In en, this message translates to:
  /// **'Next month, {month}'**
  String goals_plan_fromNext(String month);

  /// Web en.json "goals.plan.fromThis".
  ///
  /// In en, this message translates to:
  /// **'This month, {month}'**
  String goals_plan_fromThis(String month);

  /// Web en.json "goals.plan.save".
  ///
  /// In en, this message translates to:
  /// **'Save the plan'**
  String get goals_plan_save;

  /// Web en.json "goals.plan.saved".
  ///
  /// In en, this message translates to:
  /// **'The plan is changed from {month}.'**
  String goals_plan_saved(String month);

  /// Web en.json "goals.plan.thisMonthUnchanged".
  ///
  /// In en, this message translates to:
  /// **'{month} stays as it is. The new amount applies from the month after.'**
  String goals_plan_thisMonthUnchanged(String month);

  /// Web en.json "goals.plan.title".
  ///
  /// In en, this message translates to:
  /// **'Change the plan for {name}'**
  String goals_plan_title(String name);

  /// Web en.json "goals.previewUnavailable".
  ///
  /// In en, this message translates to:
  /// **'The figures could not be worked out just now. You can still save.'**
  String get goals_previewUnavailable;

  /// Web en.json "goals.projection.late".
  ///
  /// In en, this message translates to:
  /// **'At {monthly} a month you reach it in {month} — {count} months after {target}.'**
  String goals_projection_late(String monthly, String month, String count, String target);

  /// Web en.json "goals.projection.lateOne".
  ///
  /// In en, this message translates to:
  /// **'At {monthly} a month you reach it in {month} — 1 month after {target}.'**
  String goals_projection_lateOne(String monthly, String month, String target);

  /// Web en.json "goals.projection.onTime".
  ///
  /// In en, this message translates to:
  /// **'At {monthly} a month you reach it in {month}, in time for {target}.'**
  String goals_projection_onTime(String monthly, String month, String target);

  /// Web en.json "goals.projection.overdue".
  ///
  /// In en, this message translates to:
  /// **'Overdue since {target}.'**
  String goals_projection_overdue(String target);

  /// Web en.json "goals.projection.overdueReaches".
  ///
  /// In en, this message translates to:
  /// **'Overdue since {target} — at {monthly} a month you reach it in {month}.'**
  String goals_projection_overdueReaches(String target, String monthly, String month);

  /// Web en.json "goals.projection.planLate".
  ///
  /// In en, this message translates to:
  /// **'Keeping to its plan, you reach it in {month} — {count} months after {target}.'**
  String goals_projection_planLate(String month, String count, String target);

  /// Web en.json "goals.projection.planLateOne".
  ///
  /// In en, this message translates to:
  /// **'Keeping to its plan, you reach it in {month} — 1 month after {target}.'**
  String goals_projection_planLateOne(String month, String target);

  /// Web en.json "goals.projection.planOnTime".
  ///
  /// In en, this message translates to:
  /// **'Keeping to its plan, you reach it in {month}, in time for {target}.'**
  String goals_projection_planOnTime(String month, String target);

  /// Web en.json "goals.projection.planOverdueReaches".
  ///
  /// In en, this message translates to:
  /// **'Overdue since {target} — keeping to its plan, you reach it in {month}.'**
  String goals_projection_planOverdueReaches(String target, String month);

  /// Web en.json "goals.projection.planReaches".
  ///
  /// In en, this message translates to:
  /// **'Keeping to its plan, you reach it in {month}.'**
  String goals_projection_planReaches(String month);

  /// Web en.json "goals.projection.reaches".
  ///
  /// In en, this message translates to:
  /// **'At {monthly} a month you reach it in {month}.'**
  String goals_projection_reaches(String monthly, String month);

  /// Web en.json "goals.projection.required".
  ///
  /// In en, this message translates to:
  /// **'{amount} a month gets you there by {target}.'**
  String goals_projection_required(String amount, String target);

  /// Web en.json "goals.reachedTag".
  ///
  /// In en, this message translates to:
  /// **'Reached'**
  String get goals_reachedTag;

  /// Web en.json "goals.reason.beyondHorizon".
  ///
  /// In en, this message translates to:
  /// **'At {monthly} a month it is more than fifty years away.'**
  String goals_reason_beyondHorizon(String monthly);

  /// Web en.json "goals.reason.noContribution".
  ///
  /// In en, this message translates to:
  /// **'No monthly amount set.'**
  String get goals_reason_noContribution;

  /// Web en.json "goals.reason.noTargetMonth".
  ///
  /// In en, this message translates to:
  /// **'There is no target month.'**
  String get goals_reason_noTargetMonth;

  /// Web en.json "goals.reason.notReachable".
  ///
  /// In en, this message translates to:
  /// **'This month started with the goal reached, so this month\'s plan no longer counts. Record the rest directly to make {target}.'**
  String goals_reason_notReachable(String target);

  /// Web en.json "goals.reason.reached".
  ///
  /// In en, this message translates to:
  /// **'Reached.'**
  String get goals_reason_reached;

  /// Web en.json "goals.reason.targetMonthPassed".
  ///
  /// In en, this message translates to:
  /// **'The month you picked has passed.'**
  String get goals_reason_targetMonthPassed;

  /// Web en.json "goals.removeContribution.body".
  ///
  /// In en, this message translates to:
  /// **'The goal\'s figure changes to match. Nothing in any account is touched.'**
  String get goals_removeContribution_body;

  /// Web en.json "goals.removeContribution.confirm".
  ///
  /// In en, this message translates to:
  /// **'Remove'**
  String get goals_removeContribution_confirm;

  /// Web en.json "goals.removeContribution.done".
  ///
  /// In en, this message translates to:
  /// **'Removed.'**
  String get goals_removeContribution_done;

  /// Web en.json "goals.removeContribution.heading".
  ///
  /// In en, this message translates to:
  /// **'Remove this from {name}?'**
  String goals_removeContribution_heading(String name);

  /// Web en.json "goals.required.beyondHorizon".
  ///
  /// In en, this message translates to:
  /// **'The month you picked is more than fifty years away, so there is no monthly figure for it.'**
  String get goals_required_beyondHorizon;

  /// Web en.json "goals.required.noContribution".
  ///
  /// In en, this message translates to:
  /// **'Set a monthly amount to see when you get there.'**
  String get goals_required_noContribution;

  /// Web en.json "goals.required.noTargetMonth".
  ///
  /// In en, this message translates to:
  /// **'There is no target month, so there is no monthly figure to aim for.'**
  String get goals_required_noTargetMonth;

  /// Web en.json "goals.required.targetMonthPassed".
  ///
  /// In en, this message translates to:
  /// **'The month you picked has passed, so there is no monthly figure to reach it by then.'**
  String get goals_required_targetMonthPassed;

  /// Web en.json "goals.row.nameAndMonth".
  ///
  /// In en, this message translates to:
  /// **'{name}, {month}'**
  String goals_row_nameAndMonth(String name, String month);

  /// Web en.json "goals.row.savedOf".
  ///
  /// In en, this message translates to:
  /// **'{saved} of {target}'**
  String goals_row_savedOf(String saved, String target);

  /// Web en.json "goals.setAside".
  ///
  /// In en, this message translates to:
  /// **'Set money aside'**
  String get goals_setAside;

  /// Web en.json "goals.takeOut".
  ///
  /// In en, this message translates to:
  /// **'Take money out'**
  String get goals_takeOut;

  /// Web en.json "goals.title".
  ///
  /// In en, this message translates to:
  /// **'Goals'**
  String get goals_title;

  /// Web en.json "goals.unarchive".
  ///
  /// In en, this message translates to:
  /// **'Restore'**
  String get goals_unarchive;

  /// Web en.json "goals.unarchived".
  ///
  /// In en, this message translates to:
  /// **'The goal is restored, and is part of the month\'s plan again.'**
  String get goals_unarchived;

  /// Web en.json "goals.updated".
  ///
  /// In en, this message translates to:
  /// **'The goal is updated.'**
  String get goals_updated;

  /// Web en.json "household.baseCurrency".
  ///
  /// In en, this message translates to:
  /// **'Base currency'**
  String get household_baseCurrency;

  /// Web en.json "household.baseCurrencyHint".
  ///
  /// In en, this message translates to:
  /// **'Totals are shown in this currency unless a member chooses another in their preferences.'**
  String get household_baseCurrencyHint;

  /// Web en.json "household.detailsHeading".
  ///
  /// In en, this message translates to:
  /// **'Details'**
  String get household_detailsHeading;

  /// Web en.json "household.lede".
  ///
  /// In en, this message translates to:
  /// **'The household is the shared pot of money everyone here sees. This instance holds one.'**
  String get household_lede;

  /// Web en.json "household.name".
  ///
  /// In en, this message translates to:
  /// **'Household name'**
  String get household_name;

  /// Web en.json "household.ownerOnlyEdit".
  ///
  /// In en, this message translates to:
  /// **'Only an owner can change these.'**
  String get household_ownerOnlyEdit;

  /// Web en.json "household.saved".
  ///
  /// In en, this message translates to:
  /// **'Household saved.'**
  String get household_saved;

  /// Web en.json "household.title".
  ///
  /// In en, this message translates to:
  /// **'Household'**
  String get household_title;

  /// Web en.json "import.account".
  ///
  /// In en, this message translates to:
  /// **'Account'**
  String get import_account;

  /// Web en.json "import.accountDiffers".
  ///
  /// In en, this message translates to:
  /// **'This file looks like it belongs to a different account from {account}. Check you chose the right one before importing.'**
  String import_accountDiffers(String account);

  /// Web en.json "import.another".
  ///
  /// In en, this message translates to:
  /// **'Import another file'**
  String get import_another;

  /// Web en.json "import.attention".
  ///
  /// In en, this message translates to:
  /// **'Needs a look'**
  String get import_attention;

  /// Web en.json "import.attentionTruncated".
  ///
  /// In en, this message translates to:
  /// **'There are more than are shown here. The counts above include all of them.'**
  String get import_attentionTruncated;

  /// Web en.json "import.balanceAfter".
  ///
  /// In en, this message translates to:
  /// **'After this import'**
  String get import_balanceAfter;

  /// Web en.json "import.balanceAfterWithSuspected".
  ///
  /// In en, this message translates to:
  /// **'If you include every one that looks typed'**
  String get import_balanceAfterWithSuspected;

  /// Web en.json "import.balanceDiffers".
  ///
  /// In en, this message translates to:
  /// **'Those two differ. That\'s usual when your opening balance or older transactions in Budget Owl don\'t match the bank\'s. Worth checking before you rely on either.'**
  String get import_balanceDiffers;

  /// Web en.json "import.balanceMatches".
  ///
  /// In en, this message translates to:
  /// **'They match.'**
  String get import_balanceMatches;

  /// Web en.json "import.balanceNow".
  ///
  /// In en, this message translates to:
  /// **'Balance in Budget Owl now'**
  String get import_balanceNow;

  /// Web en.json "import.bankSays".
  ///
  /// In en, this message translates to:
  /// **'Your bank says the balance is {bank}. After this import, Budget Owl will say {after}.'**
  String import_bankSays(String bank, String after);

  /// Web en.json "import.cellSays".
  ///
  /// In en, this message translates to:
  /// **'It says:'**
  String get import_cellSays;

  /// Web en.json "import.charset".
  ///
  /// In en, this message translates to:
  /// **'Character set'**
  String get import_charset;

  /// Web en.json "import.charsetAuto".
  ///
  /// In en, this message translates to:
  /// **'Work it out from the file'**
  String get import_charsetAuto;

  /// Web en.json "import.charsetHint".
  ///
  /// In en, this message translates to:
  /// **'The file doesn\'t say which character set it uses. Choose the one where names and accents read correctly.'**
  String get import_charsetHint;

  /// Web en.json "import.charsetQuestion".
  ///
  /// In en, this message translates to:
  /// **'Does this text look right?'**
  String get import_charsetQuestion;

  /// Web en.json "import.charsetSamples".
  ///
  /// In en, this message translates to:
  /// **'Read as {charset}'**
  String import_charsetSamples(String charset);

  /// Web en.json "import.chooseAccount".
  ///
  /// In en, this message translates to:
  /// **'Choose an account'**
  String get import_chooseAccount;

  /// Web en.json "import.chooseFormat".
  ///
  /// In en, this message translates to:
  /// **'Choose a format'**
  String get import_chooseFormat;

  /// Web en.json "import.chosenFile".
  ///
  /// In en, this message translates to:
  /// **'Selected: {size}.'**
  String import_chosenFile(String size);

  /// Web en.json "import.column".
  ///
  /// In en, this message translates to:
  /// **'Column {number}'**
  String import_column(String number);

  /// Web en.json "import.columnLabelled".
  ///
  /// In en, this message translates to:
  /// **'Column {number} · {label}'**
  String import_columnLabelled(String number, String label);

  /// Web en.json "import.columnRole".
  ///
  /// In en, this message translates to:
  /// **'What column {number} holds'**
  String import_columnRole(String number);

  /// Web en.json "import.columns".
  ///
  /// In en, this message translates to:
  /// **'Columns'**
  String get import_columns;

  /// Web en.json "import.columnsCaption".
  ///
  /// In en, this message translates to:
  /// **'The first rows of your file, with what each column holds'**
  String get import_columnsCaption;

  /// Web en.json "import.committing".
  ///
  /// In en, this message translates to:
  /// **'Importing…'**
  String get import_committing;

  /// Web en.json "import.confirm".
  ///
  /// In en, this message translates to:
  /// **'Import {count} transactions into {account}'**
  String import_confirm(String count, String account);

  /// Web en.json "import.count.duplicate".
  ///
  /// In en, this message translates to:
  /// **'Already in your ledger'**
  String get import_count_duplicate;

  /// Web en.json "import.count.new".
  ///
  /// In en, this message translates to:
  /// **'New'**
  String get import_count_new;

  /// Web en.json "import.count.suspected".
  ///
  /// In en, this message translates to:
  /// **'Look like ones you typed'**
  String get import_count_suspected;

  /// Web en.json "import.count.unreadable".
  ///
  /// In en, this message translates to:
  /// **'Couldn\'t be read'**
  String get import_count_unreadable;

  /// Web en.json "import.count.zero".
  ///
  /// In en, this message translates to:
  /// **'For zero, skipped'**
  String get import_count_zero;

  /// Web en.json "import.dateFormat".
  ///
  /// In en, this message translates to:
  /// **'Date format'**
  String get import_dateFormat;

  /// Web en.json "import.dateFormat.compact".
  ///
  /// In en, this message translates to:
  /// **'Year, month and day together (20260803)'**
  String get import_dateFormat_compact;

  /// Web en.json "import.dateFormat.dmy".
  ///
  /// In en, this message translates to:
  /// **'Day/month/year (3/8/2026)'**
  String get import_dateFormat_dmy;

  /// Web en.json "import.dateFormat.dmyDashes".
  ///
  /// In en, this message translates to:
  /// **'Day-month-year (03-08-2026)'**
  String get import_dateFormat_dmyDashes;

  /// Web en.json "import.dateFormat.dmyDots".
  ///
  /// In en, this message translates to:
  /// **'Day.month.year (03.08.2026)'**
  String get import_dateFormat_dmyDots;

  /// Web en.json "import.dateFormat.dmyPadded".
  ///
  /// In en, this message translates to:
  /// **'Day/month/year (03/08/2026)'**
  String get import_dateFormat_dmyPadded;

  /// Web en.json "import.dateFormat.dmyShort".
  ///
  /// In en, this message translates to:
  /// **'Day/month/two-digit year (3/8/26)'**
  String get import_dateFormat_dmyShort;

  /// Web en.json "import.dateFormat.mdy".
  ///
  /// In en, this message translates to:
  /// **'Month/day/year (8/3/2026)'**
  String get import_dateFormat_mdy;

  /// Web en.json "import.dateFormat.mdyPadded".
  ///
  /// In en, this message translates to:
  /// **'Month/day/year (08/03/2026)'**
  String get import_dateFormat_mdyPadded;

  /// Web en.json "import.dateFormat.mdyQuicken".
  ///
  /// In en, this message translates to:
  /// **'Quicken style (8/3\'26)'**
  String get import_dateFormat_mdyQuicken;

  /// Web en.json "import.dateFormat.mdyShort".
  ///
  /// In en, this message translates to:
  /// **'Month/day/two-digit year (8/3/26)'**
  String get import_dateFormat_mdyShort;

  /// Web en.json "import.dateFormat.ymd".
  ///
  /// In en, this message translates to:
  /// **'Year-month-day (2026-08-03)'**
  String get import_dateFormat_ymd;

  /// Web en.json "import.dateFormatAmbiguous".
  ///
  /// In en, this message translates to:
  /// **'The dates in this file can be read more than one way. Choose how your bank writes them.'**
  String get import_dateFormatAmbiguous;

  /// Web en.json "import.dateFormatChoose".
  ///
  /// In en, this message translates to:
  /// **'Choose a date format'**
  String get import_dateFormatChoose;

  /// Web en.json "import.dateFormatHint".
  ///
  /// In en, this message translates to:
  /// **'Choose how your bank writes dates.'**
  String get import_dateFormatHint;

  /// Web en.json "import.dateRange".
  ///
  /// In en, this message translates to:
  /// **'{from} to {to}'**
  String import_dateRange(String from, String to);

  /// Web en.json "import.dates".
  ///
  /// In en, this message translates to:
  /// **'Statement dates'**
  String get import_dates;

  /// Web en.json "import.decimal.comma".
  ///
  /// In en, this message translates to:
  /// **'A comma (1,50)'**
  String get import_decimal_comma;

  /// Web en.json "import.decimal.dot".
  ///
  /// In en, this message translates to:
  /// **'A dot (1.50)'**
  String get import_decimal_dot;

  /// Web en.json "import.decimalSeparator".
  ///
  /// In en, this message translates to:
  /// **'Decimal separator'**
  String get import_decimalSeparator;

  /// Web en.json "import.delimiter".
  ///
  /// In en, this message translates to:
  /// **'Columns are separated by'**
  String get import_delimiter;

  /// Web en.json "import.delimiter.comma".
  ///
  /// In en, this message translates to:
  /// **'Commas'**
  String get import_delimiter_comma;

  /// Web en.json "import.delimiter.pipe".
  ///
  /// In en, this message translates to:
  /// **'Vertical bars'**
  String get import_delimiter_pipe;

  /// Web en.json "import.delimiter.semicolon".
  ///
  /// In en, this message translates to:
  /// **'Semicolons'**
  String get import_delimiter_semicolon;

  /// Web en.json "import.delimiter.tab".
  ///
  /// In en, this message translates to:
  /// **'Tabs'**
  String get import_delimiter_tab;

  /// Web en.json "import.directionIn".
  ///
  /// In en, this message translates to:
  /// **'Text that means money in'**
  String get import_directionIn;

  /// Web en.json "import.directionOut".
  ///
  /// In en, this message translates to:
  /// **'Text that means money out'**
  String get import_directionOut;

  /// Web en.json "import.done".
  ///
  /// In en, this message translates to:
  /// **'Imported {count} transactions into {account}.'**
  String import_done(String count, String account);

  /// Web en.json "import.doneDetail".
  ///
  /// In en, this message translates to:
  /// **'They\'re in Needs a category, ready for you to sort.'**
  String get import_doneDetail;

  /// Web en.json "import.dropHint".
  ///
  /// In en, this message translates to:
  /// **'Or drop the file onto this box.'**
  String get import_dropHint;

  /// Web en.json "import.duplicatesIntro".
  ///
  /// In en, this message translates to:
  /// **'Already in your ledger from an earlier import, so they\'re skipped.'**
  String get import_duplicatesIntro;

  /// Web en.json "import.excludeAll".
  ///
  /// In en, this message translates to:
  /// **'Leave all out'**
  String get import_excludeAll;

  /// Web en.json "import.file".
  ///
  /// In en, this message translates to:
  /// **'Statement file'**
  String get import_file;

  /// Web en.json "import.fileHint".
  ///
  /// In en, this message translates to:
  /// **'OFX, QFX, QIF or CSV, up to 5 MB. It is read by your own Budget Owl server and isn\'t kept after the import.'**
  String get import_fileHint;

  /// Web en.json "import.format".
  ///
  /// In en, this message translates to:
  /// **'Format'**
  String get import_format;

  /// Web en.json "import.format.CSV".
  ///
  /// In en, this message translates to:
  /// **'CSV (a spreadsheet export)'**
  String get import_format_CSV;

  /// Web en.json "import.format.OFX".
  ///
  /// In en, this message translates to:
  /// **'OFX or QFX'**
  String get import_format_OFX;

  /// Web en.json "import.format.QIF".
  ///
  /// In en, this message translates to:
  /// **'QIF'**
  String get import_format_QIF;

  /// Web en.json "import.formatHint".
  ///
  /// In en, this message translates to:
  /// **'Picked from the file\'s name. Change it if that\'s wrong; the file is checked either way.'**
  String get import_formatHint;

  /// Web en.json "import.goToAccounts".
  ///
  /// In en, this message translates to:
  /// **'Go to accounts'**
  String get import_goToAccounts;

  /// Web en.json "import.grouping.apostrophe".
  ///
  /// In en, this message translates to:
  /// **'An apostrophe (1\'000)'**
  String get import_grouping_apostrophe;

  /// Web en.json "import.grouping.comma".
  ///
  /// In en, this message translates to:
  /// **'A comma (1,000)'**
  String get import_grouping_comma;

  /// Web en.json "import.grouping.dot".
  ///
  /// In en, this message translates to:
  /// **'A dot (1.000)'**
  String get import_grouping_dot;

  /// Web en.json "import.grouping.narrow".
  ///
  /// In en, this message translates to:
  /// **'A narrow space'**
  String get import_grouping_narrow;

  /// Web en.json "import.grouping.nbsp".
  ///
  /// In en, this message translates to:
  /// **'A non-breaking space'**
  String get import_grouping_nbsp;

  /// Web en.json "import.grouping.none".
  ///
  /// In en, this message translates to:
  /// **'None'**
  String get import_grouping_none;

  /// Web en.json "import.grouping.space".
  ///
  /// In en, this message translates to:
  /// **'A space (1 000)'**
  String get import_grouping_space;

  /// Web en.json "import.groupingSeparator".
  ///
  /// In en, this message translates to:
  /// **'Thousands separator'**
  String get import_groupingSeparator;

  /// Web en.json "import.headerRow".
  ///
  /// In en, this message translates to:
  /// **'Header row'**
  String get import_headerRow;

  /// Web en.json "import.headerRowHint".
  ///
  /// In en, this message translates to:
  /// **'Which line holds the column names. 0 if the file has none.'**
  String get import_headerRowHint;

  /// Web en.json "import.history".
  ///
  /// In en, this message translates to:
  /// **'Recent imports'**
  String get import_history;

  /// Web en.json "import.historyCounts".
  ///
  /// In en, this message translates to:
  /// **'{imported} added · {duplicate} already there · {excluded} left out · {unreadable} unreadable'**
  String import_historyCounts(
    String imported,
    String duplicate,
    String excluded,
    String unreadable,
  );

  /// Web en.json "import.historyEmpty".
  ///
  /// In en, this message translates to:
  /// **'Nothing imported yet.'**
  String get import_historyEmpty;

  /// Web en.json "import.historyNothingLeft".
  ///
  /// In en, this message translates to:
  /// **'Nothing left to undo'**
  String get import_historyNothingLeft;

  /// Web en.json "import.historyRow".
  ///
  /// In en, this message translates to:
  /// **'{account} · {format} · {date}'**
  String import_historyRow(String account, String format, String date);

  /// Web en.json "import.historyUndo".
  ///
  /// In en, this message translates to:
  /// **'Undo'**
  String get import_historyUndo;

  /// Web en.json "import.identical".
  ///
  /// In en, this message translates to:
  /// **'Identical rows'**
  String get import_identical;

  /// Web en.json "import.identicalCount".
  ///
  /// In en, this message translates to:
  /// **'× {count}'**
  String import_identicalCount(String count);

  /// Web en.json "import.identicalIntro".
  ///
  /// In en, this message translates to:
  /// **'These rows are exactly alike, which is normal (two coffees on one day). Each one is imported.'**
  String get import_identicalIntro;

  /// Web en.json "import.include".
  ///
  /// In en, this message translates to:
  /// **'Include'**
  String get import_include;

  /// Web en.json "import.includeAll".
  ///
  /// In en, this message translates to:
  /// **'Include all'**
  String get import_includeAll;

  /// Web en.json "import.lede".
  ///
  /// In en, this message translates to:
  /// **'Bring in transactions from a file your bank exported. You\'ll see exactly what will be added before anything is.'**
  String get import_lede;

  /// Web en.json "import.line".
  ///
  /// In en, this message translates to:
  /// **'Line {row}'**
  String import_line(String row);

  /// Web en.json "import.lineColumn".
  ///
  /// In en, this message translates to:
  /// **'Line {row}, column {column}'**
  String import_lineColumn(String row, String column);

  /// Web en.json "import.mappingChanged".
  ///
  /// In en, this message translates to:
  /// **'This file\'s columns aren\'t the same as the last file you imported into this account. Check the settings before you update the preview.'**
  String get import_mappingChanged;

  /// Web en.json "import.mappingSaved".
  ///
  /// In en, this message translates to:
  /// **'These are the settings you saved for this account.'**
  String get import_mappingSaved;

  /// Web en.json "import.mappingSuggested".
  ///
  /// In en, this message translates to:
  /// **'Budget Owl guessed these settings from the file. Check them, then update the preview.'**
  String get import_mappingSuggested;

  /// Web en.json "import.moneyIn".
  ///
  /// In en, this message translates to:
  /// **'Money in'**
  String get import_moneyIn;

  /// Web en.json "import.moneyOut.columns".
  ///
  /// In en, this message translates to:
  /// **'A separate column'**
  String get import_moneyOut_columns;

  /// Web en.json "import.moneyOut.negative".
  ///
  /// In en, this message translates to:
  /// **'A negative amount'**
  String get import_moneyOut_negative;

  /// Web en.json "import.moneyOut.positive".
  ///
  /// In en, this message translates to:
  /// **'A positive amount (flip the signs)'**
  String get import_moneyOut_positive;

  /// Web en.json "import.moneyOutAs".
  ///
  /// In en, this message translates to:
  /// **'Money out appears as'**
  String get import_moneyOutAs;

  /// Web en.json "import.moneyOutColumnsHint".
  ///
  /// In en, this message translates to:
  /// **'Mark the columns below as Money out and Money in, or as In or out.'**
  String get import_moneyOutColumnsHint;

  /// Web en.json "import.moneyOutTotal".
  ///
  /// In en, this message translates to:
  /// **'Money out'**
  String get import_moneyOutTotal;

  /// Web en.json "import.noAccountsBody".
  ///
  /// In en, this message translates to:
  /// **'An import needs an account to put the transactions in. Add one first.'**
  String get import_noAccountsBody;

  /// Web en.json "import.nothingNew".
  ///
  /// In en, this message translates to:
  /// **'There\'s nothing new to add from this file.'**
  String get import_nothingNew;

  /// Web en.json "import.nothingWritten".
  ///
  /// In en, this message translates to:
  /// **'Nothing is added until you confirm.'**
  String get import_nothingWritten;

  /// Web en.json "import.outOfDate".
  ///
  /// In en, this message translates to:
  /// **'You have changed how the file is read. Update the preview to see what that does before you import.'**
  String get import_outOfDate;

  /// Web en.json "import.preview".
  ///
  /// In en, this message translates to:
  /// **'Preview the import'**
  String get import_preview;

  /// Web en.json "import.readFailed".
  ///
  /// In en, this message translates to:
  /// **'Your browser couldn\'t open that file. Try choosing it again.'**
  String get import_readFailed;

  /// Web en.json "import.ready".
  ///
  /// In en, this message translates to:
  /// **'Preview ready.'**
  String get import_ready;

  /// Web en.json "import.resembles".
  ///
  /// In en, this message translates to:
  /// **'Looks like the one already in your ledger on {date}.'**
  String import_resembles(String date);

  /// Web en.json "import.reusedIdentifier".
  ///
  /// In en, this message translates to:
  /// **'Your bank\'s file used the same reference for more than one transaction, so Budget Owl told them apart by date, amount and payee instead.'**
  String get import_reusedIdentifier;

  /// Web en.json "import.role.AMOUNT".
  ///
  /// In en, this message translates to:
  /// **'Amount'**
  String get import_role_AMOUNT;

  /// Web en.json "import.role.CREDIT".
  ///
  /// In en, this message translates to:
  /// **'Money in'**
  String get import_role_CREDIT;

  /// Web en.json "import.role.CURRENCY".
  ///
  /// In en, this message translates to:
  /// **'Currency'**
  String get import_role_CURRENCY;

  /// Web en.json "import.role.DATE".
  ///
  /// In en, this message translates to:
  /// **'Date'**
  String get import_role_DATE;

  /// Web en.json "import.role.DEBIT".
  ///
  /// In en, this message translates to:
  /// **'Money out'**
  String get import_role_DEBIT;

  /// Web en.json "import.role.DIRECTION".
  ///
  /// In en, this message translates to:
  /// **'In or out'**
  String get import_role_DIRECTION;

  /// Web en.json "import.role.NOTE".
  ///
  /// In en, this message translates to:
  /// **'Note'**
  String get import_role_NOTE;

  /// Web en.json "import.role.PAYEE".
  ///
  /// In en, this message translates to:
  /// **'Payee'**
  String get import_role_PAYEE;

  /// Web en.json "import.role.REFERENCE".
  ///
  /// In en, this message translates to:
  /// **'Reference'**
  String get import_role_REFERENCE;

  /// Web en.json "import.role.none".
  ///
  /// In en, this message translates to:
  /// **'Not used'**
  String get import_role_none;

  /// Web en.json "import.row.row-amount-too-large".
  ///
  /// In en, this message translates to:
  /// **'The amount is larger than Budget Owl records.'**
  String get import_row_row_amount_too_large;

  /// Web en.json "import.row.row-amount-too-precise".
  ///
  /// In en, this message translates to:
  /// **'The amount has more than four decimal places.'**
  String get import_row_row_amount_too_precise;

  /// Web en.json "import.row.row-amount-unreadable".
  ///
  /// In en, this message translates to:
  /// **'The amount can\'t be read.'**
  String get import_row_row_amount_unreadable;

  /// Web en.json "import.row.row-columns-short".
  ///
  /// In en, this message translates to:
  /// **'It has fewer columns than the rows around it.'**
  String get import_row_row_columns_short;

  /// Web en.json "import.row.row-currency-mismatch".
  ///
  /// In en, this message translates to:
  /// **'It\'s in a different currency from the account.'**
  String get import_row_row_currency_mismatch;

  /// Web en.json "import.row.row-date-ambiguous".
  ///
  /// In en, this message translates to:
  /// **'The date can be read more than one way.'**
  String get import_row_row_date_ambiguous;

  /// Web en.json "import.row.row-date-out-of-range".
  ///
  /// In en, this message translates to:
  /// **'The date is too far in the past or the future.'**
  String get import_row_row_date_out_of_range;

  /// Web en.json "import.row.row-date-unreadable".
  ///
  /// In en, this message translates to:
  /// **'The date can\'t be read.'**
  String get import_row_row_date_unreadable;

  /// Web en.json "import.row.row-entity-unknown".
  ///
  /// In en, this message translates to:
  /// **'It contains a character code Budget Owl doesn\'t recognise.'**
  String get import_row_row_entity_unknown;

  /// Web en.json "import.row.row-missing-field".
  ///
  /// In en, this message translates to:
  /// **'It\'s missing a date or an amount.'**
  String get import_row_row_missing_field;

  /// Web en.json "import.row.unknown".
  ///
  /// In en, this message translates to:
  /// **'It can\'t be read.'**
  String get import_row_unknown;

  /// Web en.json "import.sample".
  ///
  /// In en, this message translates to:
  /// **'The first rows to be added'**
  String get import_sample;

  /// Web en.json "import.saveMapping".
  ///
  /// In en, this message translates to:
  /// **'Remember these settings for this account'**
  String get import_saveMapping;

  /// Web en.json "import.saveMappingHint".
  ///
  /// In en, this message translates to:
  /// **'Saved when you import, so the next file from this bank needs no setting up.'**
  String get import_saveMappingHint;

  /// Web en.json "import.stale".
  ///
  /// In en, this message translates to:
  /// **'Your ledger changed since this preview, so nothing was imported. This is the preview again, with what\'s there now.'**
  String get import_stale;

  /// Web en.json "import.staleDuplicates".
  ///
  /// In en, this message translates to:
  /// **'{count} of these were added by somebody else a moment ago.'**
  String import_staleDuplicates(String count);

  /// Web en.json "import.startOver".
  ///
  /// In en, this message translates to:
  /// **'Start over'**
  String get import_startOver;

  /// Web en.json "import.stepFile".
  ///
  /// In en, this message translates to:
  /// **'The file, and where it goes'**
  String get import_stepFile;

  /// Web en.json "import.stepMapping".
  ///
  /// In en, this message translates to:
  /// **'How to read it'**
  String get import_stepMapping;

  /// Web en.json "import.stepPreview".
  ///
  /// In en, this message translates to:
  /// **'What will be added'**
  String get import_stepPreview;

  /// Web en.json "import.summary".
  ///
  /// In en, this message translates to:
  /// **'Summary'**
  String get import_summary;

  /// Web en.json "import.suspectedIntro".
  ///
  /// In en, this message translates to:
  /// **'These look like transactions somebody already typed in, so they\'re left out unless you tick them.'**
  String get import_suspectedIntro;

  /// Web en.json "import.textCut".
  ///
  /// In en, this message translates to:
  /// **'Some payees or notes were longer than Budget Owl keeps, so they\'ve been shortened.'**
  String get import_textCut;

  /// Web en.json "import.title".
  ///
  /// In en, this message translates to:
  /// **'Import a statement'**
  String get import_title;

  /// Web en.json "import.tooLarge".
  ///
  /// In en, this message translates to:
  /// **'That file is larger than 5 MB, the most Budget Owl reads at once. Export a shorter date range from your bank and import it in parts.'**
  String get import_tooLarge;

  /// Web en.json "import.undo".
  ///
  /// In en, this message translates to:
  /// **'Undo this import'**
  String get import_undo;

  /// Web en.json "import.undoHeading".
  ///
  /// In en, this message translates to:
  /// **'Undo this import?'**
  String get import_undoHeading;

  /// Web en.json "import.undoMessage".
  ///
  /// In en, this message translates to:
  /// **'This removes the {count} transactions this import added to {account}. Any of them since linked to a bill, a debt payment or an envelope lose that link. Rows you have paired into a transfer stay.'**
  String import_undoMessage(String count, String account);

  /// Web en.json "import.undone".
  ///
  /// In en, this message translates to:
  /// **'Import undone. {count} transactions removed.'**
  String import_undone(String count);

  /// Web en.json "import.unreadableIntro".
  ///
  /// In en, this message translates to:
  /// **'These lines couldn\'t be read and won\'t be imported. Fix them in the file and import it again if you need them.'**
  String get import_unreadableIntro;

  /// Web en.json "import.updatePreview".
  ///
  /// In en, this message translates to:
  /// **'Update preview'**
  String get import_updatePreview;

  /// Web en.json "import.viewLedger".
  ///
  /// In en, this message translates to:
  /// **'See them in the ledger'**
  String get import_viewLedger;

  /// Web en.json "import.viewerBody".
  ///
  /// In en, this message translates to:
  /// **'Your role in this household is read-only. An owner or a member can import a statement for you.'**
  String get import_viewerBody;

  /// Web en.json "import.viewerHeading".
  ///
  /// In en, this message translates to:
  /// **'Viewers can\'t import statements'**
  String get import_viewerHeading;

  /// Web en.json "import.working".
  ///
  /// In en, this message translates to:
  /// **'Reading the file…'**
  String get import_working;

  /// Web en.json "insights.change.down".
  ///
  /// In en, this message translates to:
  /// **'down {percent}'**
  String insights_change_down(String percent);

  /// Web en.json "insights.change.downSmall".
  ///
  /// In en, this message translates to:
  /// **'down less than 1%'**
  String get insights_change_downSmall;

  /// Web en.json "insights.change.flat".
  ///
  /// In en, this message translates to:
  /// **'flat'**
  String get insights_change_flat;

  /// Web en.json "insights.change.new".
  ///
  /// In en, this message translates to:
  /// **'new'**
  String get insights_change_new;

  /// Web en.json "insights.change.up".
  ///
  /// In en, this message translates to:
  /// **'up {percent}'**
  String insights_change_up(String percent);

  /// Web en.json "insights.change.upSmall".
  ///
  /// In en, this message translates to:
  /// **'up less than 1%'**
  String get insights_change_upSmall;

  /// Web en.json "insights.chart.label".
  ///
  /// In en, this message translates to:
  /// **'Income and spending by month'**
  String get insights_chart_label;

  /// Web en.json "insights.chart.negative".
  ///
  /// In en, this message translates to:
  /// **'A month where refunds were more than spending is drawn at zero. The table shows its real figure.'**
  String get insights_chart_negative;

  /// Web en.json "insights.coincident".
  ///
  /// In en, this message translates to:
  /// **'Income and spending were the same every month, so they are drawn as one line.'**
  String get insights_coincident;

  /// Web en.json "insights.currency.label".
  ///
  /// In en, this message translates to:
  /// **'Currency'**
  String get insights_currency_label;

  /// Web en.json "insights.currency.note".
  ///
  /// In en, this message translates to:
  /// **'Each currency is reported on its own, with no combined figure: adding them together would need an exchange rate.'**
  String get insights_currency_note;

  /// Web en.json "insights.download".
  ///
  /// In en, this message translates to:
  /// **'Download CSV'**
  String get insights_download;

  /// Web en.json "insights.downloadIncome".
  ///
  /// In en, this message translates to:
  /// **'of income and spending by month'**
  String get insights_downloadIncome;

  /// Web en.json "insights.downloadWhere".
  ///
  /// In en, this message translates to:
  /// **'of where {month} went'**
  String insights_downloadWhere(String month);

  /// Web en.json "insights.downloading".
  ///
  /// In en, this message translates to:
  /// **'Preparing the file…'**
  String get insights_downloading;

  /// Web en.json "insights.empty".
  ///
  /// In en, this message translates to:
  /// **'Nothing recorded between {from} and {to} yet.'**
  String insights_empty(String from, String to);

  /// Web en.json "insights.empty.link".
  ///
  /// In en, this message translates to:
  /// **'Record a transaction'**
  String get insights_empty_link;

  /// Web en.json "insights.envelopes".
  ///
  /// In en, this message translates to:
  /// **'Insights counts the transactions in your ledger. Amounts logged straight into an envelope don\'t appear here.'**
  String get insights_envelopes;

  /// Web en.json "insights.figures.caption".
  ///
  /// In en, this message translates to:
  /// **'Income and spending by month, {range}, in {currency}.'**
  String insights_figures_caption(String range, String currency);

  /// Web en.json "insights.figures.income".
  ///
  /// In en, this message translates to:
  /// **'Income'**
  String get insights_figures_income;

  /// Web en.json "insights.figures.kept".
  ///
  /// In en, this message translates to:
  /// **'Kept'**
  String get insights_figures_kept;

  /// Web en.json "insights.figures.month".
  ///
  /// In en, this message translates to:
  /// **'Month'**
  String get insights_figures_month;

  /// Web en.json "insights.figures.show".
  ///
  /// In en, this message translates to:
  /// **'Show the figures'**
  String get insights_figures_show;

  /// Web en.json "insights.figures.spending".
  ///
  /// In en, this message translates to:
  /// **'Spending'**
  String get insights_figures_spending;

  /// Web en.json "insights.figures.uncategorisedIn".
  ///
  /// In en, this message translates to:
  /// **'Needs a category, in'**
  String get insights_figures_uncategorisedIn;

  /// Web en.json "insights.figures.uncategorisedOut".
  ///
  /// In en, this message translates to:
  /// **'Needs a category, out'**
  String get insights_figures_uncategorisedOut;

  /// Web en.json "insights.income.heading".
  ///
  /// In en, this message translates to:
  /// **'Income vs spending'**
  String get insights_income_heading;

  /// Web en.json "insights.keptLong".
  ///
  /// In en, this message translates to:
  /// **'Kept an average of {amount} a month. Best month was {month}, at {best}.'**
  String insights_keptLong(String amount, String month, String best);

  /// Web en.json "insights.keptLongPartial".
  ///
  /// In en, this message translates to:
  /// **'Kept an average of {amount} a month over the {count} complete months. Best month was {month}, at {best}.'**
  String insights_keptLongPartial(String amount, String count, String month, String best);

  /// Web en.json "insights.keptLongPartialOne".
  ///
  /// In en, this message translates to:
  /// **'Kept {amount} in the one complete month so far, {month}.'**
  String insights_keptLongPartialOne(String amount, String month);

  /// Web en.json "insights.keptShort".
  ///
  /// In en, this message translates to:
  /// **'Avg kept {amount}/mo'**
  String insights_keptShort(String amount);

  /// Web en.json "insights.lede".
  ///
  /// In en, this message translates to:
  /// **'What actually happened in your ledger, month by month. Every figure is added up from your transactions each time you open this page.'**
  String get insights_lede;

  /// Web en.json "insights.legend.income".
  ///
  /// In en, this message translates to:
  /// **'Income'**
  String get insights_legend_income;

  /// Web en.json "insights.legend.spending".
  ///
  /// In en, this message translates to:
  /// **'Spending'**
  String get insights_legend_spending;

  /// Web en.json "insights.range.12".
  ///
  /// In en, this message translates to:
  /// **'12 mo'**
  String get insights_range_12;

  /// Web en.json "insights.range.12.name".
  ///
  /// In en, this message translates to:
  /// **'Last 12 months'**
  String get insights_range_12_name;

  /// Web en.json "insights.range.3".
  ///
  /// In en, this message translates to:
  /// **'3 mo'**
  String get insights_range_3;

  /// Web en.json "insights.range.3.name".
  ///
  /// In en, this message translates to:
  /// **'Last 3 months'**
  String get insights_range_3_name;

  /// Web en.json "insights.range.6".
  ///
  /// In en, this message translates to:
  /// **'6 mo'**
  String get insights_range_6;

  /// Web en.json "insights.range.6.name".
  ///
  /// In en, this message translates to:
  /// **'Last 6 months'**
  String get insights_range_6_name;

  /// Web en.json "insights.range.label".
  ///
  /// In en, this message translates to:
  /// **'Range'**
  String get insights_range_label;

  /// Web en.json "insights.said.income".
  ///
  /// In en, this message translates to:
  /// **'Income: {first} in {firstMonth}, to {last} in {lastMonth}.'**
  String insights_said_income(String first, String firstMonth, String last, String lastMonth);

  /// Web en.json "insights.said.spending".
  ///
  /// In en, this message translates to:
  /// **'Spending: {first} in {firstMonth}, to {last} in {lastMonth}.'**
  String insights_said_spending(String first, String firstMonth, String last, String lastMonth);

  /// Web en.json "insights.soFar".
  ///
  /// In en, this message translates to:
  /// **'{month} (so far)'**
  String insights_soFar(String month);

  /// Web en.json "insights.status.month".
  ///
  /// In en, this message translates to:
  /// **'Showing where {month} went.'**
  String insights_status_month(String month);

  /// Web en.json "insights.status.range".
  ///
  /// In en, this message translates to:
  /// **'Showing {range}.'**
  String insights_status_range(String range);

  /// Web en.json "insights.title".
  ///
  /// In en, this message translates to:
  /// **'Insights'**
  String get insights_title;

  /// Web en.json "insights.uncategorised.link".
  ///
  /// In en, this message translates to:
  /// **'Give them a category'**
  String get insights_uncategorised_link;

  /// Web en.json "insights.uncategorised.many.both".
  ///
  /// In en, this message translates to:
  /// **'{count} transactions need a category, so {out} out and {inValue} in aren\'t counted as income or spending.'**
  String insights_uncategorised_many_both(String count, String out, String inValue);

  /// Web en.json "insights.uncategorised.many.single".
  ///
  /// In en, this message translates to:
  /// **'{count} transactions need a category, so {amount} isn\'t counted as income or spending.'**
  String insights_uncategorised_many_single(String count, String amount);

  /// Web en.json "insights.uncategorised.one.both".
  ///
  /// In en, this message translates to:
  /// **'1 transaction needs a category, so {out} out and {inValue} in aren\'t counted as income or spending.'**
  String insights_uncategorised_one_both(String out, String inValue);

  /// Web en.json "insights.uncategorised.one.single".
  ///
  /// In en, this message translates to:
  /// **'1 transaction needs a category, so {amount} isn\'t counted as income or spending.'**
  String insights_uncategorised_one_single(String amount);

  /// Web en.json "insights.where.archived".
  ///
  /// In en, this message translates to:
  /// **'Archived'**
  String get insights_where_archived;

  /// Web en.json "insights.where.heading".
  ///
  /// In en, this message translates to:
  /// **'Where {month} went'**
  String insights_where_heading(String month);

  /// Web en.json "insights.where.headingSoFar".
  ///
  /// In en, this message translates to:
  /// **'Where {month} has gone so far'**
  String insights_where_headingSoFar(String month);

  /// Web en.json "insights.where.hideChildren".
  ///
  /// In en, this message translates to:
  /// **'Hide what\'s in {name}'**
  String insights_where_hideChildren(String name);

  /// Web en.json "insights.where.list".
  ///
  /// In en, this message translates to:
  /// **'Spending by category in {month}'**
  String insights_where_list(String month);

  /// Web en.json "insights.where.navigation".
  ///
  /// In en, this message translates to:
  /// **'Choose a month'**
  String get insights_where_navigation;

  /// Web en.json "insights.where.next".
  ///
  /// In en, this message translates to:
  /// **'Next month'**
  String get insights_where_next;

  /// Web en.json "insights.where.nothing".
  ///
  /// In en, this message translates to:
  /// **'Nothing was spent in {month}.'**
  String insights_where_nothing(String month);

  /// Web en.json "insights.where.previous".
  ///
  /// In en, this message translates to:
  /// **'Previous month'**
  String get insights_where_previous;

  /// Web en.json "insights.where.showAll".
  ///
  /// In en, this message translates to:
  /// **'Show all {count} categories'**
  String insights_where_showAll(String count);

  /// Web en.json "insights.where.showChildren".
  ///
  /// In en, this message translates to:
  /// **'Show what\'s in {name}'**
  String insights_where_showChildren(String name);

  /// Web en.json "insights.where.showFewer".
  ///
  /// In en, this message translates to:
  /// **'Show fewer categories'**
  String get insights_where_showFewer;

  /// Web en.json "insights.where.total".
  ///
  /// In en, this message translates to:
  /// **'{amount} spent, against {compared} in {comparedMonth}.'**
  String insights_where_total(String amount, String compared, String comparedMonth);

  /// Web en.json "insights.where.uncategorised.many".
  ///
  /// In en, this message translates to:
  /// **'Needs a category: {amount} across {count} transactions, not in the list above.'**
  String insights_where_uncategorised_many(String amount, String count);

  /// Web en.json "insights.where.uncategorised.one".
  ///
  /// In en, this message translates to:
  /// **'Needs a category: {amount} across 1 transaction, not in the list above.'**
  String insights_where_uncategorised_one(String amount);

  /// Web en.json "invite.another".
  ///
  /// In en, this message translates to:
  /// **'Invite another person'**
  String get invite_another;

  /// Web en.json "invite.copied".
  ///
  /// In en, this message translates to:
  /// **'Link copied.'**
  String get invite_copied;

  /// Web en.json "invite.copy".
  ///
  /// In en, this message translates to:
  /// **'Copy link'**
  String get invite_copy;

  /// Web en.json "invite.copyManual".
  ///
  /// In en, this message translates to:
  /// **'The link is selected. Copy it with your keyboard.'**
  String get invite_copyManual;

  /// Web en.json "invite.create".
  ///
  /// In en, this message translates to:
  /// **'Create link'**
  String get invite_create;

  /// Web en.json "invite.creating".
  ///
  /// In en, this message translates to:
  /// **'Creating link…'**
  String get invite_creating;

  /// Web en.json "invite.credentialWarning".
  ///
  /// In en, this message translates to:
  /// **'Treat this link like a password: anyone who has it can join this household as {role}. It works once and expires {expires}.'**
  String invite_credentialWarning(String role, String expires);

  /// Web en.json "invite.email".
  ///
  /// In en, this message translates to:
  /// **'Their email address'**
  String get invite_email;

  /// Web en.json "invite.emailHint".
  ///
  /// In en, this message translates to:
  /// **'If they are new to this instance, this becomes the address they sign in with — so check it. The link is what lets them in; the address is who they become.'**
  String get invite_emailHint;

  /// Web en.json "invite.intro".
  ///
  /// In en, this message translates to:
  /// **'Create a link and send it to them however you like — a message, or read it out. There is no email.'**
  String get invite_intro;

  /// Web en.json "invite.linkLabel".
  ///
  /// In en, this message translates to:
  /// **'Invitation link'**
  String get invite_linkLabel;

  /// Web en.json "invite.linkUnavailable".
  ///
  /// In en, this message translates to:
  /// **'The link could not be built. Cancel this invitation and create a new one.'**
  String get invite_linkUnavailable;

  /// Web en.json "invite.revoke".
  ///
  /// In en, this message translates to:
  /// **'Revoke link'**
  String get invite_revoke;

  /// Web en.json "invite.revoked".
  ///
  /// In en, this message translates to:
  /// **'This link has been revoked and can no longer be used.'**
  String get invite_revoked;

  /// Web en.json "invite.role".
  ///
  /// In en, this message translates to:
  /// **'Their role'**
  String get invite_role;

  /// Web en.json "invite.shareIntro".
  ///
  /// In en, this message translates to:
  /// **'Share this link with {email}. This is the only time it is shown.'**
  String invite_shareIntro(String email);

  /// Web en.json "invite.title".
  ///
  /// In en, this message translates to:
  /// **'Invite someone'**
  String get invite_title;

  /// Web en.json "join.accept".
  ///
  /// In en, this message translates to:
  /// **'Accept and join'**
  String get join_accept;

  /// Web en.json "join.accountNote".
  ///
  /// In en, this message translates to:
  /// **'New here? Enter your name and choose a password. Already have an account on this instance? Leave both fields empty and you will be added with it.'**
  String get join_accountNote;

  /// Web en.json "join.disclosure.heading".
  ///
  /// In en, this message translates to:
  /// **'Before you join: who can see your finances'**
  String get join_disclosure_heading;

  /// Web en.json "join.disclosure.nothingHidden".
  ///
  /// In en, this message translates to:
  /// **'Budget Owl does not hide your data from them, and everyone else in the household can see it too.'**
  String get join_disclosure_nothingHidden;

  /// Web en.json "join.disclosure.operator".
  ///
  /// In en, this message translates to:
  /// **'The person who runs this instance can see everything you record in it: every transaction, every balance, every note.'**
  String get join_disclosure_operator;

  /// Web en.json "join.disclosure.trust".
  ///
  /// In en, this message translates to:
  /// **'Only join if you are comfortable sharing your finances with the person who runs this server.'**
  String get join_disclosure_trust;

  /// Mobile only (l10n/mobile.en.json "join.existingAccount").
  ///
  /// In en, this message translates to:
  /// **'I already have an account on this server'**
  String get join_existingAccount;

  /// Web en.json "join.formHeading".
  ///
  /// In en, this message translates to:
  /// **'Accept the invitation'**
  String get join_formHeading;

  /// Web en.json "join.joinedAccountCreated".
  ///
  /// In en, this message translates to:
  /// **'Your account has been created. Sign in with {email}.'**
  String join_joinedAccountCreated(String email);

  /// Web en.json "join.joinedHeading".
  ///
  /// In en, this message translates to:
  /// **'Welcome to {household}'**
  String join_joinedHeading(String household);

  /// Web en.json "join.joinedRole".
  ///
  /// In en, this message translates to:
  /// **'You joined as {role}.'**
  String join_joinedRole(String role);

  /// Web en.json "join.joinedTitle".
  ///
  /// In en, this message translates to:
  /// **'You have joined'**
  String get join_joinedTitle;

  /// Web en.json "join.joining".
  ///
  /// In en, this message translates to:
  /// **'Joining…'**
  String get join_joining;

  /// Web en.json "join.lede".
  ///
  /// In en, this message translates to:
  /// **'Someone has invited you to share a household\'s budget on this Budget Owl instance.'**
  String get join_lede;

  /// Web en.json "join.name".
  ///
  /// In en, this message translates to:
  /// **'Your name'**
  String get join_name;

  /// Web en.json "join.password".
  ///
  /// In en, this message translates to:
  /// **'Choose a password'**
  String get join_password;

  /// Mobile only (l10n/mobile.en.json "join.pasteHeading").
  ///
  /// In en, this message translates to:
  /// **'Paste your invitation link'**
  String get join_pasteHeading;

  /// Mobile only (l10n/mobile.en.json "join.pasteHint").
  ///
  /// In en, this message translates to:
  /// **'The link you were sent. It starts with this server\'s address and has /join/ in it.'**
  String get join_pasteHint;

  /// Mobile only (l10n/mobile.en.json "join.pasteLabel").
  ///
  /// In en, this message translates to:
  /// **'Invitation link'**
  String get join_pasteLabel;

  /// Mobile only (l10n/mobile.en.json "join.problem.notALink").
  ///
  /// In en, this message translates to:
  /// **'That isn\'t an invitation link. It should be this server\'s address followed by /join/ and a long code.'**
  String get join_problem_notALink;

  /// Mobile only (l10n/mobile.en.json "join.problem.otherHost").
  ///
  /// In en, this message translates to:
  /// **'That invitation is for {host}, not the server you entered. Change the server first if you meant that one.'**
  String join_problem_otherHost(String host);

  /// Web en.json "join.signInNow".
  ///
  /// In en, this message translates to:
  /// **'Sign in'**
  String get join_signInNow;

  /// Web en.json "join.signedInBody".
  ///
  /// In en, this message translates to:
  /// **'An invitation can only be accepted by someone who is signed out. Sign out here, and this page will let you accept it.'**
  String get join_signedInBody;

  /// Web en.json "join.signedInHeading".
  ///
  /// In en, this message translates to:
  /// **'You are already signed in'**
  String get join_signedInHeading;

  /// Web en.json "join.title".
  ///
  /// In en, this message translates to:
  /// **'Join a household'**
  String get join_title;

  /// Web en.json "join.toSignIn".
  ///
  /// In en, this message translates to:
  /// **'Go to sign in'**
  String get join_toSignIn;

  /// Web en.json "join.unusableTitle".
  ///
  /// In en, this message translates to:
  /// **'This invitation cannot be used'**
  String get join_unusableTitle;

  /// Mobile only (l10n/mobile.en.json "login.deviceLabel").
  ///
  /// In en, this message translates to:
  /// **'Name for this phone'**
  String get login_deviceLabel;

  /// Mobile only (l10n/mobile.en.json "login.deviceLabelHint").
  ///
  /// In en, this message translates to:
  /// **'Shown on your devices list, so you can tell which phone to sign out.'**
  String get login_deviceLabelHint;

  /// Web en.json "login.email".
  ///
  /// In en, this message translates to:
  /// **'Email address'**
  String get login_email;

  /// Web en.json "login.heading".
  ///
  /// In en, this message translates to:
  /// **'Your account'**
  String get login_heading;

  /// Web en.json "login.lede".
  ///
  /// In en, this message translates to:
  /// **'Welcome back. Sign in to see your household\'s budget.'**
  String get login_lede;

  /// Web en.json "login.notice.created".
  ///
  /// In en, this message translates to:
  /// **'Your account is ready. Sign in to continue.'**
  String get login_notice_created;

  /// Web en.json "login.notice.expired".
  ///
  /// In en, this message translates to:
  /// **'Your session ended. Sign in again to continue.'**
  String get login_notice_expired;

  /// Web en.json "login.notice.joined".
  ///
  /// In en, this message translates to:
  /// **'You have joined the household. Sign in to continue.'**
  String get login_notice_joined;

  /// Web en.json "login.password".
  ///
  /// In en, this message translates to:
  /// **'Password'**
  String get login_password;

  /// Mobile only (l10n/mobile.en.json "login.passwordLoginDisabled").
  ///
  /// In en, this message translates to:
  /// **'This server has turned off signing in with an email and password, and the app can only sign in that way for now. Sign in on the web instead.'**
  String get login_passwordLoginDisabled;

  /// Mobile only (l10n/mobile.en.json "login.server").
  ///
  /// In en, this message translates to:
  /// **'Signing in to {host}'**
  String login_server(String host);

  /// Web en.json "login.signIn".
  ///
  /// In en, this message translates to:
  /// **'Sign in'**
  String get login_signIn;

  /// Web en.json "login.signingIn".
  ///
  /// In en, this message translates to:
  /// **'Signing in…'**
  String get login_signingIn;

  /// Web en.json "login.title".
  ///
  /// In en, this message translates to:
  /// **'Sign in'**
  String get login_title;

  /// Web en.json "members.heading".
  ///
  /// In en, this message translates to:
  /// **'Members'**
  String get members_heading;

  /// Web en.json "members.invite".
  ///
  /// In en, this message translates to:
  /// **'Invite someone'**
  String get members_invite;

  /// Web en.json "members.joined".
  ///
  /// In en, this message translates to:
  /// **'Joined {date}'**
  String members_joined(String date);

  /// Web en.json "members.leave".
  ///
  /// In en, this message translates to:
  /// **'Leave'**
  String get members_leave;

  /// Web en.json "members.leaveHeading".
  ///
  /// In en, this message translates to:
  /// **'Leave this household?'**
  String get members_leaveHeading;

  /// Web en.json "members.leaveMessage".
  ///
  /// In en, this message translates to:
  /// **'You will lose access straight away. What you recorded stays in the household, and you will need a new invitation to come back.'**
  String get members_leaveMessage;

  /// Web en.json "members.remove".
  ///
  /// In en, this message translates to:
  /// **'Remove'**
  String get members_remove;

  /// Web en.json "members.removeHeading".
  ///
  /// In en, this message translates to:
  /// **'Remove {name}?'**
  String members_removeHeading(String name);

  /// Web en.json "members.removeMessage".
  ///
  /// In en, this message translates to:
  /// **'{name} will lose access to this household straight away and be signed out everywhere. What they recorded stays in the household.'**
  String members_removeMessage(String name);

  /// Web en.json "members.roleFor".
  ///
  /// In en, this message translates to:
  /// **'Role for {name}'**
  String members_roleFor(String name);

  /// Web en.json "members.soleOwner".
  ///
  /// In en, this message translates to:
  /// **'You are the only owner. Make someone else an owner before you can leave.'**
  String get members_soleOwner;

  /// Mobile only (l10n/mobile.en.json "more.changeServer").
  ///
  /// In en, this message translates to:
  /// **'Change server'**
  String get more_changeServer;

  /// Mobile only (l10n/mobile.en.json "more.changeServerHeading").
  ///
  /// In en, this message translates to:
  /// **'Change server?'**
  String get more_changeServerHeading;

  /// Mobile only (l10n/mobile.en.json "more.changeServerMessage").
  ///
  /// In en, this message translates to:
  /// **'You\'ll be signed out of {host}, and everything Budget Owl keeps on this phone is erased. Your household\'s data stays on the server.'**
  String more_changeServerMessage(String host);

  /// Mobile only (l10n/mobile.en.json "more.server").
  ///
  /// In en, this message translates to:
  /// **'Server'**
  String get more_server;

  /// Mobile only (l10n/mobile.en.json "more.signOutHeading").
  ///
  /// In en, this message translates to:
  /// **'Sign out?'**
  String get more_signOutHeading;

  /// Mobile only (l10n/mobile.en.json "more.signOutMessage").
  ///
  /// In en, this message translates to:
  /// **'Everything Budget Owl keeps on this phone is erased. Your household\'s data stays on the server.'**
  String get more_signOutMessage;

  /// Mobile only (l10n/mobile.en.json "more.title").
  ///
  /// In en, this message translates to:
  /// **'More'**
  String get more_title;

  /// Web en.json "nav.accounts".
  ///
  /// In en, this message translates to:
  /// **'Accounts'**
  String get nav_accounts;

  /// Web en.json "nav.admin".
  ///
  /// In en, this message translates to:
  /// **'Admin'**
  String get nav_admin;

  /// Web en.json "nav.bills".
  ///
  /// In en, this message translates to:
  /// **'Bills'**
  String get nav_bills;

  /// Web en.json "nav.categories".
  ///
  /// In en, this message translates to:
  /// **'Categories'**
  String get nav_categories;

  /// Web en.json "nav.checkIn".
  ///
  /// In en, this message translates to:
  /// **'Check-in'**
  String get nav_checkIn;

  /// Web en.json "nav.debtPlan".
  ///
  /// In en, this message translates to:
  /// **'Debt plan'**
  String get nav_debtPlan;

  /// Web en.json "nav.devices".
  ///
  /// In en, this message translates to:
  /// **'Devices'**
  String get nav_devices;

  /// Web en.json "nav.envelopes".
  ///
  /// In en, this message translates to:
  /// **'Envelopes'**
  String get nav_envelopes;

  /// Web en.json "nav.goals".
  ///
  /// In en, this message translates to:
  /// **'Goals'**
  String get nav_goals;

  /// Web en.json "nav.household".
  ///
  /// In en, this message translates to:
  /// **'Household'**
  String get nav_household;

  /// Mobile only (l10n/mobile.en.json "nav.import").
  ///
  /// In en, this message translates to:
  /// **'Import'**
  String get nav_import;

  /// Web en.json "nav.insights".
  ///
  /// In en, this message translates to:
  /// **'Insights'**
  String get nav_insights;

  /// Web en.json "nav.label".
  ///
  /// In en, this message translates to:
  /// **'Main'**
  String get nav_label;

  /// Mobile only (l10n/mobile.en.json "nav.more").
  ///
  /// In en, this message translates to:
  /// **'More'**
  String get nav_more;

  /// Web en.json "nav.preferences".
  ///
  /// In en, this message translates to:
  /// **'Preferences'**
  String get nav_preferences;

  /// Web en.json "nav.signOut".
  ///
  /// In en, this message translates to:
  /// **'Sign out'**
  String get nav_signOut;

  /// Web en.json "nav.transactions".
  ///
  /// In en, this message translates to:
  /// **'Transactions'**
  String get nav_transactions;

  /// Web en.json "noHousehold.body".
  ///
  /// In en, this message translates to:
  /// **'You are signed in, but nobody has added you to a household. Ask the person who runs this instance for an invitation link, then open it in this browser.'**
  String get noHousehold_body;

  /// Mobile only (l10n/mobile.en.json "noHousehold.bodyPhone").
  ///
  /// In en, this message translates to:
  /// **'You are signed in, but nobody has added you to a household. Ask the person who runs this server for an invitation link, then paste it on the connect screen.'**
  String get noHousehold_bodyPhone;

  /// Web en.json "noHousehold.title".
  ///
  /// In en, this message translates to:
  /// **'You are not part of a household yet'**
  String get noHousehold_title;

  /// Mobile only (l10n/mobile.en.json "offline.dayAgo").
  ///
  /// In en, this message translates to:
  /// **'1 day ago'**
  String get offline_dayAgo;

  /// Mobile only (l10n/mobile.en.json "offline.daysAgo").
  ///
  /// In en, this message translates to:
  /// **'{count} days ago'**
  String offline_daysAgo(String count);

  /// Mobile only (l10n/mobile.en.json "offline.hourAgo").
  ///
  /// In en, this message translates to:
  /// **'1 hour ago'**
  String get offline_hourAgo;

  /// Mobile only (l10n/mobile.en.json "offline.hoursAgo").
  ///
  /// In en, this message translates to:
  /// **'{count} hours ago'**
  String offline_hoursAgo(String count);

  /// Mobile only (l10n/mobile.en.json "offline.justNow").
  ///
  /// In en, this message translates to:
  /// **'just now'**
  String get offline_justNow;

  /// Mobile only (l10n/mobile.en.json "offline.minuteAgo").
  ///
  /// In en, this message translates to:
  /// **'1 minute ago'**
  String get offline_minuteAgo;

  /// Mobile only (l10n/mobile.en.json "offline.minutesAgo").
  ///
  /// In en, this message translates to:
  /// **'{count} minutes ago'**
  String offline_minutesAgo(String count);

  /// Mobile only (l10n/mobile.en.json "offline.noCopy").
  ///
  /// In en, this message translates to:
  /// **'You\'re offline, and this phone has no saved copy of this yet.'**
  String get offline_noCopy;

  /// Mobile only (l10n/mobile.en.json "offline.staleNoAnswer").
  ///
  /// In en, this message translates to:
  /// **'Last updated {ago} — the server didn\'t answer'**
  String offline_staleNoAnswer(String ago);

  /// Mobile only (l10n/mobile.en.json "offline.staleOffline").
  ///
  /// In en, this message translates to:
  /// **'Last updated {ago} — you\'re offline'**
  String offline_staleOffline(String ago);

  /// Mobile only (l10n/mobile.en.json "offline.versionUnconfirmed").
  ///
  /// In en, this message translates to:
  /// **'Changes are off until the server confirms it runs a version this app works with. Refresh to check again.'**
  String get offline_versionUnconfirmed;

  /// Mobile only (l10n/mobile.en.json "offline.write").
  ///
  /// In en, this message translates to:
  /// **'You\'re offline — this needs a connection'**
  String get offline_write;

  /// Web en.json "pairing.account".
  ///
  /// In en, this message translates to:
  /// **'Other account'**
  String get pairing_account;

  /// Web en.json "pairing.allAccounts".
  ///
  /// In en, this message translates to:
  /// **'All other accounts'**
  String get pairing_allAccounts;

  /// Web en.json "pairing.candidate".
  ///
  /// In en, this message translates to:
  /// **'{date} · {account} · {amount}'**
  String pairing_candidate(String date, String account, String amount);

  /// Web en.json "pairing.candidatesLabel".
  ///
  /// In en, this message translates to:
  /// **'Rows to choose from'**
  String get pairing_candidatesLabel;

  /// Web en.json "pairing.chooseFirst".
  ///
  /// In en, this message translates to:
  /// **'Choose the other row first.'**
  String get pairing_chooseFirst;

  /// Web en.json "pairing.confirm".
  ///
  /// In en, this message translates to:
  /// **'Pair these two rows'**
  String get pairing_confirm;

  /// Web en.json "pairing.intro".
  ///
  /// In en, this message translates to:
  /// **'Choose the row in your other account that this money moved to or from. Nothing is chosen for you.'**
  String get pairing_intro;

  /// Web en.json "pairing.loading".
  ///
  /// In en, this message translates to:
  /// **'Finding rows…'**
  String get pairing_loading;

  /// Web en.json "pairing.loss".
  ///
  /// In en, this message translates to:
  /// **'Both rows will lose their category and payee, and stop counting as income or spending. Splitting them later brings back two rows with no category.'**
  String get pairing_loss;

  /// Web en.json "pairing.none".
  ///
  /// In en, this message translates to:
  /// **'No rows match. Try another account or other dates.'**
  String get pairing_none;

  /// Web en.json "pairing.paired".
  ///
  /// In en, this message translates to:
  /// **'Paired into a transfer.'**
  String get pairing_paired;

  /// Web en.json "pairing.split".
  ///
  /// In en, this message translates to:
  /// **'Split'**
  String get pairing_split;

  /// Web en.json "pairing.splitDone".
  ///
  /// In en, this message translates to:
  /// **'Split into two rows. Both need a category.'**
  String get pairing_splitDone;

  /// Web en.json "pairing.splitHeading".
  ///
  /// In en, this message translates to:
  /// **'Split this transfer into two rows?'**
  String get pairing_splitHeading;

  /// Web en.json "pairing.splitMessage".
  ///
  /// In en, this message translates to:
  /// **'Both rows stay, with their amounts and dates, but come back with no category and no payee, so they\'ll be in Needs a category. They\'ll count as income and spending again.'**
  String get pairing_splitMessage;

  /// Web en.json "pairing.thisRow".
  ///
  /// In en, this message translates to:
  /// **'This row'**
  String get pairing_thisRow;

  /// Web en.json "pairing.title".
  ///
  /// In en, this message translates to:
  /// **'Pair this with the other half of a transfer'**
  String get pairing_title;

  /// Mobile only (l10n/mobile.en.json "placeholder.body").
  ///
  /// In en, this message translates to:
  /// **'This screen is coming to the app in the next update. It works on the web today.'**
  String get placeholder_body;

  /// Web en.json "preferences.currency".
  ///
  /// In en, this message translates to:
  /// **'Show amounts in'**
  String get preferences_currency;

  /// Web en.json "preferences.currencyHint".
  ///
  /// In en, this message translates to:
  /// **'Each account keeps its own currency; this is the one totals are shown in for you.'**
  String get preferences_currencyHint;

  /// Web en.json "preferences.followDevice".
  ///
  /// In en, this message translates to:
  /// **'Follow this device'**
  String get preferences_followDevice;

  /// Web en.json "preferences.followHousehold".
  ///
  /// In en, this message translates to:
  /// **'Follow the household ({currency})'**
  String preferences_followHousehold(String currency);

  /// Web en.json "preferences.heading".
  ///
  /// In en, this message translates to:
  /// **'Display'**
  String get preferences_heading;

  /// Web en.json "preferences.lede".
  ///
  /// In en, this message translates to:
  /// **'These are yours alone. They change what you see, never what is recorded, so everyone in the household can read the same figures their own way.'**
  String get preferences_lede;

  /// Web en.json "preferences.locale".
  ///
  /// In en, this message translates to:
  /// **'Language and number format'**
  String get preferences_locale;

  /// Web en.json "preferences.localeHint".
  ///
  /// In en, this message translates to:
  /// **'Text appears in this language where a translation exists, and in English otherwise. Dates and numbers always follow your choice.'**
  String get preferences_localeHint;

  /// Web en.json "preferences.saved".
  ///
  /// In en, this message translates to:
  /// **'Preferences saved.'**
  String get preferences_saved;

  /// Web en.json "preferences.title".
  ///
  /// In en, this message translates to:
  /// **'Your preferences'**
  String get preferences_title;

  /// Mobile only (l10n/mobile.en.json "privacy.hidden").
  ///
  /// In en, this message translates to:
  /// **'Budget Owl is hidden while the app is in the background.'**
  String get privacy_hidden;

  /// Web en.json "role.MEMBER".
  ///
  /// In en, this message translates to:
  /// **'Member'**
  String get role_MEMBER;

  /// Web en.json "role.MEMBER.description".
  ///
  /// In en, this message translates to:
  /// **'can see and change the household\'s money'**
  String get role_MEMBER_description;

  /// Web en.json "role.OWNER".
  ///
  /// In en, this message translates to:
  /// **'Owner'**
  String get role_OWNER;

  /// Web en.json "role.OWNER.description".
  ///
  /// In en, this message translates to:
  /// **'everything, including inviting and removing people'**
  String get role_OWNER_description;

  /// Web en.json "role.VIEWER".
  ///
  /// In en, this message translates to:
  /// **'Viewer'**
  String get role_VIEWER;

  /// Web en.json "role.VIEWER.description".
  ///
  /// In en, this message translates to:
  /// **'can see the household\'s money, but not change it'**
  String get role_VIEWER_description;

  /// Web en.json "setup.accountHeading".
  ///
  /// In en, this message translates to:
  /// **'Your account'**
  String get setup_accountHeading;

  /// Web en.json "setup.baseCurrency".
  ///
  /// In en, this message translates to:
  /// **'Base currency'**
  String get setup_baseCurrency;

  /// Web en.json "setup.baseCurrencyHint".
  ///
  /// In en, this message translates to:
  /// **'The currency totals are shown in by default. Each member can choose to see another.'**
  String get setup_baseCurrencyHint;

  /// Web en.json "setup.create".
  ///
  /// In en, this message translates to:
  /// **'Create account'**
  String get setup_create;

  /// Web en.json "setup.creating".
  ///
  /// In en, this message translates to:
  /// **'Creating account…'**
  String get setup_creating;

  /// Web en.json "setup.email".
  ///
  /// In en, this message translates to:
  /// **'Email address'**
  String get setup_email;

  /// Web en.json "setup.householdName".
  ///
  /// In en, this message translates to:
  /// **'Household name'**
  String get setup_householdName;

  /// Web en.json "setup.lede".
  ///
  /// In en, this message translates to:
  /// **'This instance is new. Create the first account — it becomes the owner of your household.'**
  String get setup_lede;

  /// Web en.json "setup.name".
  ///
  /// In en, this message translates to:
  /// **'Your name'**
  String get setup_name;

  /// Web en.json "setup.operatorNote".
  ///
  /// In en, this message translates to:
  /// **'You are the person running this instance, so you will be able to see everything anyone in your household records. Anyone you invite is told that before they join.'**
  String get setup_operatorNote;

  /// Web en.json "setup.password".
  ///
  /// In en, this message translates to:
  /// **'Password'**
  String get setup_password;

  /// Web en.json "setup.passwordHint".
  ///
  /// In en, this message translates to:
  /// **'At least {min} characters. A long phrase is stronger than a clever short one.'**
  String setup_passwordHint(String min);

  /// Web en.json "setup.title".
  ///
  /// In en, this message translates to:
  /// **'Set up Budget Owl'**
  String get setup_title;

  /// Web en.json "theme.toDark".
  ///
  /// In en, this message translates to:
  /// **'Switch to dark theme'**
  String get theme_toDark;

  /// Web en.json "theme.toLight".
  ///
  /// In en, this message translates to:
  /// **'Switch to light theme'**
  String get theme_toLight;

  /// Web en.json "title.accounts".
  ///
  /// In en, this message translates to:
  /// **'Accounts'**
  String get title_accounts;

  /// Web en.json "title.admin".
  ///
  /// In en, this message translates to:
  /// **'Admin'**
  String get title_admin;

  /// Web en.json "title.bills".
  ///
  /// In en, this message translates to:
  /// **'Bills & income'**
  String get title_bills;

  /// Web en.json "title.categories".
  ///
  /// In en, this message translates to:
  /// **'Categories'**
  String get title_categories;

  /// Web en.json "title.checkIn".
  ///
  /// In en, this message translates to:
  /// **'Weekly check-in'**
  String get title_checkIn;

  /// Web en.json "title.debtDetail".
  ///
  /// In en, this message translates to:
  /// **'Debt'**
  String get title_debtDetail;

  /// Web en.json "title.debtPlan".
  ///
  /// In en, this message translates to:
  /// **'Debt plan'**
  String get title_debtPlan;

  /// Web en.json "title.devices".
  ///
  /// In en, this message translates to:
  /// **'Devices'**
  String get title_devices;

  /// Web en.json "title.envelopes".
  ///
  /// In en, this message translates to:
  /// **'Envelopes'**
  String get title_envelopes;

  /// Web en.json "title.goalDetail".
  ///
  /// In en, this message translates to:
  /// **'Goal'**
  String get title_goalDetail;

  /// Web en.json "title.goals".
  ///
  /// In en, this message translates to:
  /// **'Goals'**
  String get title_goals;

  /// Web en.json "title.household".
  ///
  /// In en, this message translates to:
  /// **'Household'**
  String get title_household;

  /// Web en.json "title.import".
  ///
  /// In en, this message translates to:
  /// **'Import a statement'**
  String get title_import;

  /// Web en.json "title.insights".
  ///
  /// In en, this message translates to:
  /// **'Insights'**
  String get title_insights;

  /// Web en.json "title.join".
  ///
  /// In en, this message translates to:
  /// **'Join a household'**
  String get title_join;

  /// Web en.json "title.login".
  ///
  /// In en, this message translates to:
  /// **'Sign in'**
  String get title_login;

  /// Web en.json "title.preferences".
  ///
  /// In en, this message translates to:
  /// **'Preferences'**
  String get title_preferences;

  /// Web en.json "title.setup".
  ///
  /// In en, this message translates to:
  /// **'Set up'**
  String get title_setup;

  /// Web en.json "title.transactions".
  ///
  /// In en, this message translates to:
  /// **'Transactions'**
  String get title_transactions;

  /// Web en.json "transactions.account".
  ///
  /// In en, this message translates to:
  /// **'Account'**
  String get transactions_account;

  /// Web en.json "transactions.accountFixedHint".
  ///
  /// In en, this message translates to:
  /// **'A transaction stays in the account it was recorded against. To move it, delete it and record it again.'**
  String get transactions_accountFixedHint;

  /// Mobile only (l10n/mobile.en.json "transactions.actionsFor").
  ///
  /// In en, this message translates to:
  /// **'Actions for {name}'**
  String transactions_actionsFor(String name);

  /// Web en.json "transactions.add".
  ///
  /// In en, this message translates to:
  /// **'Record a transaction'**
  String get transactions_add;

  /// Web en.json "transactions.addFirst".
  ///
  /// In en, this message translates to:
  /// **'Record your first transaction'**
  String get transactions_addFirst;

  /// Web en.json "transactions.addTitle".
  ///
  /// In en, this message translates to:
  /// **'Record a transaction'**
  String get transactions_addTitle;

  /// Web en.json "transactions.allAccounts".
  ///
  /// In en, this message translates to:
  /// **'All accounts'**
  String get transactions_allAccounts;

  /// Web en.json "transactions.allCategories".
  ///
  /// In en, this message translates to:
  /// **'All categories'**
  String get transactions_allCategories;

  /// Web en.json "transactions.amount".
  ///
  /// In en, this message translates to:
  /// **'Amount'**
  String get transactions_amount;

  /// Web en.json "transactions.amountHint".
  ///
  /// In en, this message translates to:
  /// **'In {currency}, the currency of the account you chose.'**
  String transactions_amountHint(String currency);

  /// Mobile only (l10n/mobile.en.json "transactions.balanceOf").
  ///
  /// In en, this message translates to:
  /// **'{account} balance'**
  String transactions_balanceOf(String account);

  /// Web en.json "transactions.betweenAccounts".
  ///
  /// In en, this message translates to:
  /// **'Between your accounts'**
  String get transactions_betweenAccounts;

  /// Web en.json "transactions.categorise".
  ///
  /// In en, this message translates to:
  /// **'Add a category'**
  String get transactions_categorise;

  /// Web en.json "transactions.category".
  ///
  /// In en, this message translates to:
  /// **'Category'**
  String get transactions_category;

  /// Web en.json "transactions.categoryPath".
  ///
  /// In en, this message translates to:
  /// **'{parent} → {name}'**
  String transactions_categoryPath(String parent, String name);

  /// Mobile only (l10n/mobile.en.json "transactions.categoryTrail").
  ///
  /// In en, this message translates to:
  /// **'{parent} › {name}'**
  String transactions_categoryTrail(String parent, String name);

  /// Mobile only (l10n/mobile.en.json "transactions.chooseCategory").
  ///
  /// In en, this message translates to:
  /// **'Choose a category'**
  String get transactions_chooseCategory;

  /// Web en.json "transactions.clearFilters".
  ///
  /// In en, this message translates to:
  /// **'Clear filters'**
  String get transactions_clearFilters;

  /// Web en.json "transactions.created".
  ///
  /// In en, this message translates to:
  /// **'Transaction recorded.'**
  String get transactions_created;

  /// Web en.json "transactions.date".
  ///
  /// In en, this message translates to:
  /// **'Date'**
  String get transactions_date;

  /// Web en.json "transactions.deleteHeading".
  ///
  /// In en, this message translates to:
  /// **'Delete this transaction?'**
  String get transactions_deleteHeading;

  /// Web en.json "transactions.deleteMessage".
  ///
  /// In en, this message translates to:
  /// **'Your account balance goes back to what it was. This can\'t be undone.'**
  String get transactions_deleteMessage;

  /// Web en.json "transactions.deleted".
  ///
  /// In en, this message translates to:
  /// **'Transaction deleted.'**
  String get transactions_deleted;

  /// Web en.json "transactions.direction".
  ///
  /// In en, this message translates to:
  /// **'Which way did the money go?'**
  String get transactions_direction;

  /// Web en.json "transactions.directionIn".
  ///
  /// In en, this message translates to:
  /// **'Money in'**
  String get transactions_directionIn;

  /// Web en.json "transactions.directionOut".
  ///
  /// In en, this message translates to:
  /// **'Money out'**
  String get transactions_directionOut;

  /// Web en.json "transactions.editTitle".
  ///
  /// In en, this message translates to:
  /// **'Edit transaction'**
  String get transactions_editTitle;

  /// Web en.json "transactions.emptyBody".
  ///
  /// In en, this message translates to:
  /// **'Nothing recorded yet. Add what you\'ve spent and your account balances will start to mean something.'**
  String get transactions_emptyBody;

  /// Web en.json "transactions.emptyReadOnly".
  ///
  /// In en, this message translates to:
  /// **'Ask an owner or member of your household to record some.'**
  String get transactions_emptyReadOnly;

  /// Mobile only (l10n/mobile.en.json "transactions.filterButton").
  ///
  /// In en, this message translates to:
  /// **'Filter'**
  String get transactions_filterButton;

  /// Web en.json "transactions.filtersLabel".
  ///
  /// In en, this message translates to:
  /// **'Narrow the list'**
  String get transactions_filtersLabel;

  /// Web en.json "transactions.from".
  ///
  /// In en, this message translates to:
  /// **'From'**
  String get transactions_from;

  /// Web en.json "transactions.goToAccounts".
  ///
  /// In en, this message translates to:
  /// **'Go to accounts'**
  String get transactions_goToAccounts;

  /// Web en.json "transactions.heading".
  ///
  /// In en, this message translates to:
  /// **'Your transactions'**
  String get transactions_heading;

  /// Web en.json "transactions.importStatement".
  ///
  /// In en, this message translates to:
  /// **'Import a statement'**
  String get transactions_importStatement;

  /// Web en.json "transactions.lede".
  ///
  /// In en, this message translates to:
  /// **'Everything that\'s happened to your money. Record what you spend as you go — you can always put a category on it later.'**
  String get transactions_lede;

  /// Web en.json "transactions.mixedCurrencies".
  ///
  /// In en, this message translates to:
  /// **'No total — this page mixes currencies.'**
  String get transactions_mixedCurrencies;

  /// Web en.json "transactions.needsCategory".
  ///
  /// In en, this message translates to:
  /// **'Needs a category'**
  String get transactions_needsCategory;

  /// Web en.json "transactions.needsCategoryFilter".
  ///
  /// In en, this message translates to:
  /// **'Needs a category'**
  String get transactions_needsCategoryFilter;

  /// Web en.json "transactions.noAccountsBody".
  ///
  /// In en, this message translates to:
  /// **'A transaction has to go somewhere, so add an account first.'**
  String get transactions_noAccountsBody;

  /// Web en.json "transactions.noMatches".
  ///
  /// In en, this message translates to:
  /// **'Nothing matches what you\'re looking for. Try widening the dates, or clearing the filters.'**
  String get transactions_noMatches;

  /// Web en.json "transactions.noPayee".
  ///
  /// In en, this message translates to:
  /// **'No payee'**
  String get transactions_noPayee;

  /// Web en.json "transactions.note".
  ///
  /// In en, this message translates to:
  /// **'Note'**
  String get transactions_note;

  /// Mobile only (l10n/mobile.en.json "transactions.noteHint").
  ///
  /// In en, this message translates to:
  /// **'Optional.'**
  String get transactions_noteHint;

  /// Web en.json "transactions.pageOf".
  ///
  /// In en, this message translates to:
  /// **'Page {page} of {pages}'**
  String transactions_pageOf(String page, String pages);

  /// Web en.json "transactions.pageTotal".
  ///
  /// In en, this message translates to:
  /// **'This page adds up to'**
  String get transactions_pageTotal;

  /// Web en.json "transactions.pagerLabel".
  ///
  /// In en, this message translates to:
  /// **'Pages of transactions'**
  String get transactions_pagerLabel;

  /// Web en.json "transactions.pair".
  ///
  /// In en, this message translates to:
  /// **'This is a transfer…'**
  String get transactions_pair;

  /// Web en.json "transactions.payee".
  ///
  /// In en, this message translates to:
  /// **'Who it was with'**
  String get transactions_payee;

  /// Web en.json "transactions.payeeHint".
  ///
  /// In en, this message translates to:
  /// **'Optional. Start typing and we\'ll suggest ones you\'ve used before.'**
  String get transactions_payeeHint;

  /// Mobile only (l10n/mobile.en.json "transactions.readOnlyRow").
  ///
  /// In en, this message translates to:
  /// **'Your role in this household can read transactions but not change them.'**
  String get transactions_readOnlyRow;

  /// Web en.json "transactions.search".
  ///
  /// In en, this message translates to:
  /// **'Search'**
  String get transactions_search;

  /// Web en.json "transactions.searchHint".
  ///
  /// In en, this message translates to:
  /// **'Looks in notes and payee names.'**
  String get transactions_searchHint;

  /// Mobile only (l10n/mobile.en.json "transactions.searchPlaceholder").
  ///
  /// In en, this message translates to:
  /// **'Search payees and notes'**
  String get transactions_searchPlaceholder;

  /// Web en.json "transactions.showing".
  ///
  /// In en, this message translates to:
  /// **'Showing {shown} of {total}'**
  String transactions_showing(String shown, String total);

  /// Web en.json "transactions.split".
  ///
  /// In en, this message translates to:
  /// **'Split into two rows'**
  String get transactions_split;

  /// Web en.json "transactions.status.CLEARED".
  ///
  /// In en, this message translates to:
  /// **'Cleared'**
  String get transactions_status_CLEARED;

  /// Web en.json "transactions.status.PENDING".
  ///
  /// In en, this message translates to:
  /// **'Pending'**
  String get transactions_status_PENDING;

  /// Web en.json "transactions.status.RECONCILED".
  ///
  /// In en, this message translates to:
  /// **'Reconciled'**
  String get transactions_status_RECONCILED;

  /// Web en.json "transactions.status.unknown".
  ///
  /// In en, this message translates to:
  /// **'Other'**
  String get transactions_status_unknown;

  /// Web en.json "transactions.statusHint".
  ///
  /// In en, this message translates to:
  /// **'Leave this as Cleared unless you\'re recording something you expect to settle later.'**
  String get transactions_statusHint;

  /// Web en.json "transactions.statusLabel".
  ///
  /// In en, this message translates to:
  /// **'Status'**
  String get transactions_statusLabel;

  /// Web en.json "transactions.title".
  ///
  /// In en, this message translates to:
  /// **'Transactions'**
  String get transactions_title;

  /// Web en.json "transactions.to".
  ///
  /// In en, this message translates to:
  /// **'To'**
  String get transactions_to;

  /// Web en.json "transactions.transfer".
  ///
  /// In en, this message translates to:
  /// **'Transfer'**
  String get transactions_transfer;

  /// Web en.json "transactions.transferIn".
  ///
  /// In en, this message translates to:
  /// **'Transfer in'**
  String get transactions_transferIn;

  /// Web en.json "transactions.transferOut".
  ///
  /// In en, this message translates to:
  /// **'Transfer out'**
  String get transactions_transferOut;

  /// Web en.json "transactions.transferPairOnly".
  ///
  /// In en, this message translates to:
  /// **'Both halves of a transfer change together, so this row can\'t be edited on its own.'**
  String get transactions_transferPairOnly;

  /// Web en.json "transactions.updated".
  ///
  /// In en, this message translates to:
  /// **'Transaction saved.'**
  String get transactions_updated;

  /// Web en.json "transfers.accountsFixed".
  ///
  /// In en, this message translates to:
  /// **'{from} → {to}'**
  String transfers_accountsFixed(String from, String to);

  /// Web en.json "transfers.accountsFixedHint".
  ///
  /// In en, this message translates to:
  /// **'A transfer stays between the two accounts you made it with, so there\'s nothing to change here. To move it to different accounts, delete it and make it again.'**
  String get transfers_accountsFixedHint;

  /// Web en.json "transfers.accountsLabel".
  ///
  /// In en, this message translates to:
  /// **'Accounts'**
  String get transfers_accountsLabel;

  /// Mobile only (l10n/mobile.en.json "transfers.accountsTrail").
  ///
  /// In en, this message translates to:
  /// **'{from} › {to}'**
  String transfers_accountsTrail(String from, String to);

  /// Web en.json "transfers.addTitle".
  ///
  /// In en, this message translates to:
  /// **'Move money between your accounts'**
  String get transfers_addTitle;

  /// Web en.json "transfers.amount".
  ///
  /// In en, this message translates to:
  /// **'Amount that left'**
  String get transfers_amount;

  /// Web en.json "transfers.amountHint".
  ///
  /// In en, this message translates to:
  /// **'In {currency}, the currency of the account it leaves. No minus sign — which way it went is the two accounts above.'**
  String transfers_amountHint(String currency);

  /// Web en.json "transfers.amountHintNoAccount".
  ///
  /// In en, this message translates to:
  /// **'Choose the account it leaves and we\'ll tell you which currency this is in.'**
  String get transfers_amountHintNoAccount;

  /// Web en.json "transfers.amountReceived".
  ///
  /// In en, this message translates to:
  /// **'Amount that arrived'**
  String get transfers_amountReceived;

  /// Web en.json "transfers.amountReceivedHint".
  ///
  /// In en, this message translates to:
  /// **'These two accounts are in different currencies, so what arrived isn\'t the same number as what left — and only your statement knows it. Copy it from there, in {received}.'**
  String transfers_amountReceivedHint(String received);

  /// Web en.json "transfers.chooseAccount".
  ///
  /// In en, this message translates to:
  /// **'Choose an account'**
  String get transfers_chooseAccount;

  /// Web en.json "transfers.created".
  ///
  /// In en, this message translates to:
  /// **'Money moved.'**
  String get transfers_created;

  /// Web en.json "transfers.date".
  ///
  /// In en, this message translates to:
  /// **'Date'**
  String get transfers_date;

  /// Web en.json "transfers.deleteHeading".
  ///
  /// In en, this message translates to:
  /// **'Delete this transfer?'**
  String get transfers_deleteHeading;

  /// Web en.json "transfers.deleteMessage".
  ///
  /// In en, this message translates to:
  /// **'Both halves go, and both account balances go back to what they were. This can\'t be undone.'**
  String get transfers_deleteMessage;

  /// Web en.json "transfers.deleted".
  ///
  /// In en, this message translates to:
  /// **'Transfer deleted.'**
  String get transfers_deleted;

  /// Web en.json "transfers.editBothHalves".
  ///
  /// In en, this message translates to:
  /// **'Changes here apply to both halves — what left one account and what arrived in the other stay in step.'**
  String get transfers_editBothHalves;

  /// Web en.json "transfers.editTitle".
  ///
  /// In en, this message translates to:
  /// **'Edit this transfer'**
  String get transfers_editTitle;

  /// Web en.json "transfers.from".
  ///
  /// In en, this message translates to:
  /// **'Out of'**
  String get transfers_from;

  /// Web en.json "transfers.impliedRate".
  ///
  /// In en, this message translates to:
  /// **'That works out at 1 {sent} = {rate} {received}.'**
  String transfers_impliedRate(String sent, String rate, String received);

  /// Web en.json "transfers.impliedRateNote".
  ///
  /// In en, this message translates to:
  /// **'Worked out by dividing the two figures above, just so you can see it. It isn\'t the rate your bank gave you — that had its fee inside it — and nothing in Budget Owl uses it.'**
  String get transfers_impliedRateNote;

  /// Web en.json "transfers.legDates".
  ///
  /// In en, this message translates to:
  /// **'The two halves are dated {from} and {to}. A date saved here moves both to it; leave it as it is and each keeps its own.'**
  String transfers_legDates(String from, String to);

  /// Web en.json "transfers.legNotes".
  ///
  /// In en, this message translates to:
  /// **'The two halves have different notes. A note saved here replaces both; leave it as it is and each keeps its own.'**
  String get transfers_legNotes;

  /// Web en.json "transfers.move".
  ///
  /// In en, this message translates to:
  /// **'Move money'**
  String get transfers_move;

  /// Web en.json "transfers.noCategoryNote".
  ///
  /// In en, this message translates to:
  /// **'Moving money between two of your own accounts isn\'t spending and isn\'t income, so a transfer has no category and nobody to pay.'**
  String get transfers_noCategoryNote;

  /// Web en.json "transfers.note".
  ///
  /// In en, this message translates to:
  /// **'Note'**
  String get transfers_note;

  /// Web en.json "transfers.noteHint".
  ///
  /// In en, this message translates to:
  /// **'Optional. Both halves of the transfer get it.'**
  String get transfers_noteHint;

  /// Web en.json "transfers.to".
  ///
  /// In en, this message translates to:
  /// **'Into'**
  String get transfers_to;

  /// Web en.json "transfers.updated".
  ///
  /// In en, this message translates to:
  /// **'Transfer saved.'**
  String get transfers_updated;

  /// Web en.json "validation.amount".
  ///
  /// In en, this message translates to:
  /// **'Enter an amount using digits and a dot, like 1250.00 or -400.'**
  String get validation_amount;

  /// Web en.json "validation.currency".
  ///
  /// In en, this message translates to:
  /// **'Enter a three-letter currency code.'**
  String get validation_currency;

  /// Web en.json "validation.email".
  ///
  /// In en, this message translates to:
  /// **'Enter a valid email address.'**
  String get validation_email;

  /// Web en.json "validation.issuerUrl".
  ///
  /// In en, this message translates to:
  /// **'Enter the provider\'s address starting with https:// — or http:// only if it runs on this same machine.'**
  String get validation_issuerUrl;

  /// Web en.json "validation.lastFour".
  ///
  /// In en, this message translates to:
  /// **'Enter exactly four digits, or leave this empty.'**
  String get validation_lastFour;

  /// Web en.json "validation.maxBytes".
  ///
  /// In en, this message translates to:
  /// **'That is too long. Use at most {max} bytes.'**
  String validation_maxBytes(String max);

  /// Web en.json "validation.maxLength".
  ///
  /// In en, this message translates to:
  /// **'Use at most {max} characters.'**
  String validation_maxLength(String max);

  /// Web en.json "validation.minLength".
  ///
  /// In en, this message translates to:
  /// **'Use at least {min} characters.'**
  String validation_minLength(String min);

  /// Web en.json "validation.positiveAmount".
  ///
  /// In en, this message translates to:
  /// **'Enter an amount using digits and a dot, like 200 or 49.99. No minus sign.'**
  String get validation_positiveAmount;

  /// Web en.json "validation.required".
  ///
  /// In en, this message translates to:
  /// **'This field is required.'**
  String get validation_required;

  /// Web en.json "validation.sameAccount".
  ///
  /// In en, this message translates to:
  /// **'Choose a different account. Money can\'t move from an account to itself.'**
  String get validation_sameAccount;

  /// Web en.json "validation.server".
  ///
  /// In en, this message translates to:
  /// **'This value was not accepted. Check it and try again.'**
  String get validation_server;
}

class _AppLocalizationsDelegate extends LocalizationsDelegate<AppLocalizations> {
  const _AppLocalizationsDelegate();

  @override
  Future<AppLocalizations> load(Locale locale) {
    return SynchronousFuture<AppLocalizations>(lookupAppLocalizations(locale));
  }

  @override
  bool isSupported(Locale locale) => <String>['en'].contains(locale.languageCode);

  @override
  bool shouldReload(_AppLocalizationsDelegate old) => false;
}

AppLocalizations lookupAppLocalizations(Locale locale) {
  // Lookup logic when only language code is specified.
  switch (locale.languageCode) {
    case 'en':
      return AppLocalizationsEn();
  }

  throw FlutterError(
    'AppLocalizations.delegate failed to load unsupported locale "$locale". This is likely '
    'an issue with the localizations generation tool. Please file an issue '
    'on GitHub with a reproducible sample app and the gen-l10n configuration '
    'that was used.',
  );
}
