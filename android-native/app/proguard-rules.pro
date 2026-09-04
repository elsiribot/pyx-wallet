# JNI exports are named for this exact class and method set.
-keep class cash.pyx.app.nativeapi.NativeBindings { *; }

# Rust looks these callback methods up by their JVM names and descriptors.
# Keeping the interfaces also pins every overriding implementation method.
-keep interface cash.pyx.app.nativeapi.NativeRequestCallback { *; }
-keep interface cash.pyx.app.nativeapi.NativeSubscriptionCallback { *; }
