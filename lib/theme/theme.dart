import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:conduit/theme/tokens.dart';

/// The single (dark) theme. Mirrors prototype.html `:root` + base rules.
ThemeData pyxTheme() {
  const scheme = ColorScheme.dark(
    surface: Palette.bg,
    onSurface: Palette.text,
    primary: Palette.accent,
    onPrimary: Palette.onAccent,
    secondary: Palette.surface2,
    onSecondary: Palette.text,
    error: Palette.red,
    onError: Palette.text,
    outline: Palette.border,
    outlineVariant: Palette.borderStrong,
    surfaceContainerHighest: Palette.surface3,
    surfaceContainerHigh: Palette.surface2,
    surfaceContainer: Palette.surface,
  );

  return ThemeData(
    useMaterial3: true,
    colorScheme: scheme,
    scaffoldBackgroundColor: Palette.bg,
    fontFamily: Fonts.ui,
    splashFactory: NoSplash.splashFactory,
    highlightColor: Colors.transparent,
    dividerTheme: const DividerThemeData(
      color: Palette.border,
      thickness: 1,
      space: 1,
    ),
    appBarTheme: const AppBarTheme(
      backgroundColor: Palette.bg,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      centerTitle: false,
      titleTextStyle: Type.screenTitle,
      iconTheme: IconThemeData(color: Palette.text),
      systemOverlayStyle: SystemUiOverlayStyle(
        statusBarColor: Colors.transparent,
        statusBarIconBrightness: Brightness.light,
        systemNavigationBarColor: Palette.bg,
        systemNavigationBarIconBrightness: Brightness.light,
      ),
    ),
    textTheme: const TextTheme(
      bodyMedium: Type.body,
      titleLarge: Type.screenTitle,
      labelLarge: Type.button,
    ),
    textSelectionTheme: const TextSelectionThemeData(
      cursorColor: Palette.accent,
      selectionColor: Palette.accent,
      selectionHandleColor: Palette.accent,
    ),
    bottomSheetTheme: const BottomSheetThemeData(
      backgroundColor: Palette.surface,
      surfaceTintColor: Colors.transparent,
      modalBarrierColor: Palette.scrim,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(Radii.sheet)),
      ),
    ),
    pageTransitionsTheme: const PageTransitionsTheme(
      builders: {
        TargetPlatform.android: PyxPageTransitionsBuilder(),
        TargetPlatform.iOS: PyxPageTransitionsBuilder(),
        TargetPlatform.linux: PyxPageTransitionsBuilder(),
        TargetPlatform.macOS: PyxPageTransitionsBuilder(),
      },
    ),
  );
}

/// Screen transition per prototype `.body-anim`: 14px slide-in + fade,
/// 260ms cubic-bezier(.22,.61,.36,1).
class PyxPageTransitionsBuilder extends PageTransitionsBuilder {
  const PyxPageTransitionsBuilder();

  @override
  Widget buildTransitions<T>(
    PageRoute<T> route,
    BuildContext context,
    Animation<double> animation,
    Animation<double> secondaryAnimation,
    Widget child,
  ) {
    final curved = CurvedAnimation(
      parent: animation,
      curve: Motion.screenCurve,
    );
    return FadeTransition(
      opacity: curved,
      child: SlideTransition(
        position: Tween<Offset>(
          begin: const Offset(14 / 390, 0),
          end: Offset.zero,
        ).animate(curved),
        child: child,
      ),
    );
  }
}
