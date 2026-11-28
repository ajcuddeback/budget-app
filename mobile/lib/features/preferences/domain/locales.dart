/// Locales offered for a member's language and number format — the web's `LOCALE_CHOICES`
/// (frontend/src/app/features/preferences/data/locales.ts), in the same order.
///
/// A short list of common regional variants, not a closed set: a member's saved locale outside it
/// is kept and shown ([localeChoices]). The server accepts any well-formed BCP 47 tag.
const List<String> localeTags = [
  'en-US',
  'en-GB',
  'de-DE',
  'fr-FR',
  'es-ES',
  'es-MX',
  'it-IT',
  'pt-BR',
  'pt-PT',
  'nl-NL',
  'sv-SE',
  'pl-PL',
  'ru-RU',
  'tr-TR',
  'ar-EG',
  'he-IL',
  'fa-IR',
  'hi-IN',
  'ja-JP',
  'ko-KR',
  'zh-CN',
];

/// Each locale named **in its own language**, as the web names it (`Intl.DisplayNames` for the tag,
/// in the tag), so a speaker finds theirs whatever language the screen is in. These are endonyms,
/// not interface text: they read the same in every translation, which is why they are data here
/// rather than ARB keys. Dart's `intl` has no display-names API to take them from.
const Map<String, String> localeEndonyms = {
  'en-US': 'American English',
  'en-GB': 'British English',
  'de-DE': 'Deutsch (Deutschland)',
  'fr-FR': 'français (France)',
  'es-ES': 'español de España',
  'es-MX': 'español de México',
  'it-IT': 'italiano (Italia)',
  'pt-BR': 'português (Brasil)',
  'pt-PT': 'português europeu',
  'nl-NL': 'Nederlands (Nederland)',
  'sv-SE': 'svenska (Sverige)',
  'pl-PL': 'polski (Polska)',
  'ru-RU': 'русский (Россия)',
  'tr-TR': 'Türkçe (Türkiye)',
  'ar-EG': 'العربية (مصر)',
  'he-IL': 'עברית (ישראל)',
  'fa-IR': 'فارسی (ایران)',
  'hi-IN': 'हिन्दी (भारत)',
  'ja-JP': '日本語 (日本)',
  'ko-KR': '한국어(대한민국)',
  'zh-CN': '中文（中国）',
};

/// The list with [current] kept in it, first, when the member saved one it does not have.
List<String> localeChoices(String? current) =>
    current != null && current.isNotEmpty && !localeTags.contains(current)
    ? [current, ...localeTags]
    : localeTags;

/// "Deutsch (Deutschland) · de-DE", or the bare tag for one this list cannot name.
String localeLabel(String tag) {
  final name = localeEndonyms[tag];
  return name == null ? tag : '$name · $tag';
}
