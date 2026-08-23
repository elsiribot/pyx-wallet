import 'package:flutter/material.dart';
import 'package:conduit/theme/components/sheet.dart';

class DrawerUtils {
  DrawerUtils._();

  /// All drawers share the prototype bottom sheet (r26 top corners, grip,
  /// scrim, 320ms slide — see theme/components/sheet.dart).
  static Future<T?> show<T>({
    required BuildContext context,
    required Widget child,
  }) {
    return showPyxSheet<T>(context, child: child);
  }

  /// Pops the current drawer and pushes a new screen.
  /// Common pattern for drawer actions that navigate to a full screen.
  static void popAndPush(BuildContext context, Widget screen) {
    Navigator.of(context).pop();
    Navigator.of(context).push(MaterialPageRoute(builder: (_) => screen));
  }
}
