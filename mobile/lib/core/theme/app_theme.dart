
import 'package:budget_owl/core/theme/tokens.dart';
import 'package:material_ui/material_ui.dart';

/// The two families, each split into its `latin` and `latin-ext` subset as the web serves them
/// (design/fonts/fonts.css). Flutter chooses a face by family, not by unicode range, so the
/// `-ext` subset is a fallback family. Anything neither covers falls through to the platform's
/// own font — a Greek or Russian reader gets readable text in another face, as on the web.
const String bodyFamily = FontTokens.body;
const String headingFamily = FontTokens.heading;
const List<String> bodyFallback = ['$bodyFamily Ext'];
const List<String> headingFallback = ['$headingFamily Ext', bodyFamily];

/// The colours and roles a widget may use, from the generated tokens. Read with
/// `context.palette`, never with a literal (non-negotiable 10).
@immutable
final class Palette extends ThemeExtension<Palette> {
  const Palette({
    required this.tokens,
    required this.incoming,
    required this.needsCategory,
    required this.avatar,
    required this.onAvatar,
    required this.avatarIncoming,
    required this.onAvatarIncoming,
  });

  final ColorTokens tokens;

  /// Money in. Text, so it steps to a deep ramp stop on a light ground and a light one on a dark
  /// ground — the web's rule for accent text (tokens.css), applied to the second accent. The
  /// mobile artboards draw incoming amounts in the second accent's 700.
  final Color incoming;

  /// "Needs a category" — marked up as well as coloured (transactions.md).
  final Color needsCategory;

  /// A row's initial, on its circle.
  final Color avatar;
  final Color onAvatar;
  final Color avatarIncoming;
  final Color onAvatarIncoming;

  static const Palette light = Palette(
    tokens: lightColors,
    incoming: RampTokens.accent2700,
    needsCategory: RampTokens.accent700,
    avatar: RampTokens.accent200,
    onAvatar: RampTokens.accent700,
    avatarIncoming: RampTokens.accent2200,
    onAvatarIncoming: RampTokens.accent2800,
  );

  static const Palette dark = Palette(
    tokens: darkColors,
    incoming: RampTokens.accent2300,
    needsCategory: RampTokens.accent300,
    avatar: RampTokens.accent800,
    onAvatar: RampTokens.accent200,
    avatarIncoming: RampTokens.accent2800,
    onAvatarIncoming: RampTokens.accent2200,
  );

  @override
  Palette copyWith() => this;

  @override
  Palette lerp(Palette? other, double t) => t < 0.5 || other == null ? this : other;
}

extension PaletteRead on BuildContext {
  Palette get palette => Theme.of(this).extension<Palette>() ?? Palette.light;

  ColorTokens get colors => palette.tokens;
}

/// A text style in one of the two families at a weight. The fonts are variable, so the weight is
/// set on the `wght` axis as well as by [FontWeight] — otherwise the engine may synthesise bold
/// from the 400 instance instead of drawing the real one.
TextStyle typeface({
  required double size,
  int weight = 400,
  bool heading = false,
  double? height,
  double? letterSpacing,
  Color? color,
}) => TextStyle(
  fontFamily: heading ? headingFamily : bodyFamily,
  fontFamilyFallback: heading ? headingFallback : bodyFallback,
  fontSize: size,
  fontWeight: FontWeight.values[(weight ~/ 100) - 1],
  fontVariations: [FontVariation('wght', weight.toDouble())],
  height: height,
  letterSpacing: letterSpacing,
  color: color,
);

ThemeData buildTheme(Brightness brightness) {
  final palette = brightness == Brightness.dark ? Palette.dark : Palette.light;
  final c = palette.tokens;
  final scheme = ColorScheme(
    brightness: brightness,
    primary: c.primary,
    onPrimary: c.onPrimary,
    secondary: c.accent2,
    onSecondary: c.text,
    error: c.accentText,
    onError: c.onPrimary,
    surface: c.bg,
    onSurface: c.text,
    surfaceContainerLowest: c.card,
    surfaceContainerLow: c.card,
    surfaceContainer: c.surface,
    surfaceContainerHigh: c.surface,
    surfaceContainerHighest: c.surface,
    onSurfaceVariant: c.muted,
    outline: c.fieldBorder,
    outlineVariant: c.divider,
  );

  final text = TextTheme(
    displaySmall: typeface(size: TypeScaleTokens.display, heading: true, height: 1.1),
    headlineSmall: typeface(size: TypeScaleTokens.display, heading: true, height: 1.1),
    titleLarge: typeface(size: TypeScaleTokens.title, heading: true, height: 1.2),
    titleMedium: typeface(size: TypeScaleTokens.body, weight: 600, height: 1.3),
    titleSmall: typeface(size: TypeScaleTokens.label, weight: 600, height: 1.3),
    bodyLarge: typeface(size: TypeScaleTokens.body, height: 1.4),
    bodyMedium: typeface(size: TypeScaleTokens.body, height: 1.4),
    bodySmall: typeface(size: TypeScaleTokens.small, height: 1.35),
    labelLarge: typeface(size: TypeScaleTokens.label, weight: 600),
    labelMedium: typeface(size: TypeScaleTokens.small, weight: 600),
    labelSmall: typeface(
      size: TypeScaleTokens.overline,
      weight: 600,
      letterSpacing: TypeScaleTokens.overline * 0.08,
    ),
  ).apply(bodyColor: c.text, displayColor: c.text);

  const pill = StadiumBorder();
  final rounded = RoundedRectangleBorder(
    borderRadius: BorderRadius.circular(RadiusTokens.md),
  );
  const minimumTap = Size(48, 48);

  return ThemeData(
    useMaterial3: true,
    brightness: brightness,
    colorScheme: scheme,
    scaffoldBackgroundColor: c.bg,
    canvasColor: c.bg,
    textTheme: text,
    fontFamily: bodyFamily,
    fontFamilyFallback: bodyFallback,
    dividerColor: c.divider,
    extensions: [palette],
    materialTapTargetSize: MaterialTapTargetSize.padded,
    appBarTheme: AppBarTheme(
      backgroundColor: c.bg,
      foregroundColor: c.text,
      surfaceTintColor: c.bg,
      elevation: 0,
      scrolledUnderElevation: 0,
      titleTextStyle: text.titleLarge,
    ),
    cardTheme: CardThemeData(
      color: c.card,
      surfaceTintColor: c.card,
      elevation: 0,
      margin: EdgeInsets.zero,
      shape: rounded,
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        backgroundColor: c.primary,
        foregroundColor: c.onPrimary,
        disabledBackgroundColor: c.divider,
        disabledForegroundColor: c.muted,
        minimumSize: minimumTap,
        shape: pill,
        textStyle: text.labelLarge,
        padding: const EdgeInsetsDirectional.symmetric(
          horizontal: SpaceTokens.s6,
          vertical: SpaceTokens.s3,
        ),
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        foregroundColor: c.text,
        minimumSize: minimumTap,
        shape: pill,
        side: BorderSide(color: c.fieldBorder),
        textStyle: text.labelLarge,
        padding: const EdgeInsetsDirectional.symmetric(
          horizontal: SpaceTokens.s6,
          vertical: SpaceTokens.s3,
        ),
      ),
    ),
    textButtonTheme: TextButtonThemeData(
      style: TextButton.styleFrom(
        foregroundColor: c.accentText,
        minimumSize: minimumTap,
        textStyle: text.labelLarge,
      ),
    ),
    floatingActionButtonTheme: FloatingActionButtonThemeData(
      backgroundColor: c.primary,
      foregroundColor: c.onPrimary,
      shape: pill,
      extendedTextStyle: text.labelLarge,
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: c.card,
      labelStyle: text.bodyMedium?.copyWith(color: c.muted),
      floatingLabelStyle: text.bodySmall?.copyWith(color: c.accentText),
      helperStyle: text.bodySmall?.copyWith(color: c.muted),
      helperMaxLines: 4,
      errorMaxLines: 4,
      errorStyle: text.bodySmall?.copyWith(color: c.accentText),
      contentPadding: const EdgeInsetsDirectional.symmetric(
        horizontal: SpaceTokens.s4,
        vertical: SpaceTokens.s3,
      ),
      border: OutlineInputBorder(
        borderRadius: BorderRadius.circular(RadiusTokens.sm),
        borderSide: BorderSide(color: c.fieldBorder),
      ),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(RadiusTokens.sm),
        borderSide: BorderSide(color: c.fieldBorder),
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(RadiusTokens.sm),
        borderSide: BorderSide(color: c.accentText, width: 2),
      ),
      errorBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(RadiusTokens.sm),
        borderSide: BorderSide(color: c.accentText, width: 2),
      ),
    ),
    chipTheme: ChipThemeData(
      backgroundColor: c.bg,
      selectedColor: c.text,
      labelStyle: text.labelLarge?.copyWith(color: c.text),
      secondaryLabelStyle: text.labelLarge?.copyWith(color: c.bg),
      side: BorderSide(color: c.fieldBorder),
      shape: pill,
      checkmarkColor: c.bg,
      padding: const EdgeInsetsDirectional.symmetric(horizontal: SpaceTokens.s2),
    ),
    navigationBarTheme: NavigationBarThemeData(
      backgroundColor: c.surface,
      surfaceTintColor: c.surface,
      indicatorColor: c.card,
      elevation: 0,
      labelTextStyle: WidgetStatePropertyAll(
        typeface(size: TypeScaleTokens.nav, weight: 600, color: c.text),
      ),
      iconTheme: WidgetStateProperty.resolveWith(
        (states) => IconThemeData(
          color: states.contains(WidgetState.selected) ? c.text : c.muted,
        ),
      ),
    ),
    bottomSheetTheme: BottomSheetThemeData(
      backgroundColor: c.bg,
      surfaceTintColor: c.bg,
      showDragHandle: true,
      dragHandleColor: c.fieldBorder,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(RadiusTokens.lg)),
      ),
    ),
    dialogTheme: DialogThemeData(
      backgroundColor: c.bg,
      surfaceTintColor: c.bg,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(RadiusTokens.lg)),
      titleTextStyle: text.titleLarge,
      contentTextStyle: text.bodyMedium,
    ),
    listTileTheme: ListTileThemeData(
      iconColor: c.muted,
      textColor: c.text,
      minVerticalPadding: SpaceTokens.s3,
      contentPadding: const EdgeInsetsDirectional.symmetric(horizontal: SpaceTokens.s4),
    ),
    snackBarTheme: SnackBarThemeData(
      backgroundColor: c.text,
      contentTextStyle: text.bodyMedium?.copyWith(color: c.bg),
      behavior: SnackBarBehavior.floating,
    ),
    segmentedButtonTheme: SegmentedButtonThemeData(
      style: ButtonStyle(
        minimumSize: const WidgetStatePropertyAll(minimumTap),
        textStyle: WidgetStatePropertyAll(text.labelLarge),
        foregroundColor: WidgetStateProperty.resolveWith(
          (states) => states.contains(WidgetState.selected) ? c.bg : c.text,
        ),
        backgroundColor: WidgetStateProperty.resolveWith(
          (states) => states.contains(WidgetState.selected) ? c.text : c.bg,
        ),
        side: WidgetStatePropertyAll(BorderSide(color: c.fieldBorder)),
      ),
    ),
    progressIndicatorTheme: ProgressIndicatorThemeData(color: c.accentText),
  );
}
