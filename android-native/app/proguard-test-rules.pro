# AndroidX Test references these source-retention annotations defensively. They
# are not needed at runtime in the isolated minified instrumentation APK.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.MustBeClosed

# The runner receives this class name as an instrumentation argument. Keep the
# class and @Test entry points stable in the separately minified test APK.
-keep class cash.pyx.app.NativeJniSmokeTest { *; }

# AndroidJUnitRunner reaches the tracing facade through optional/reflection-
# tolerant paths that R8 cannot prove from the minified instrumentation APK.
-keep class androidx.tracing.** { *; }
