# BurnPony R8 rules. Minification is ON for release builds since 1.0.
#
# Almost everything reflective is covered by consumer rules shipped inside
# the libraries themselves: Moshi bundles the conditional keeps for its
# KSP-generated adapters (every DTO here is @JsonClass(generateAdapter =
# true); no reflective Moshi exists in this app), Room keeps its generated
# implementations, OkHttp/coroutines/Firebase ship their own. ZXing core and
# the crypto core use no reflection at all. What remains here is small.

# Readable stack traces in release crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# The auditable core keeps its real names: crash lines in com.burnpony.core
# should be legible to anyone comparing against the public source.
-keepnames class com.burnpony.core.** { *; }

# OkHttp optional-platform warnings (classes intentionally absent).
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
