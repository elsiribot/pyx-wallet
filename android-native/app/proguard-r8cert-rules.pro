# AndroidJUnitRunner runs inside the target process for connected tests and
# loads this facade dynamically before discovering tests. This certification-
# only rule does not alter the production release shrinker graph.
-keep class androidx.tracing.** { *; }

# The Android plugin deduplicates Kotlin stdlib from the instrumentation APK
# because it is also on the target graph. Keep the certification target's copy
# for Kotlin-based AndroidX Test startup; production release remains unaffected.
-keep class kotlin.** { *; }
