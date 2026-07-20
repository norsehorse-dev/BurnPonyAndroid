# BurnPony R8 rules. Shrinking is disabled until Phase 6 (release prep); when
# it is enabled these keep the wire contract intact.
# Moshi codegen adapters are referenced reflectively by generated code only;
# no reflective Moshi is used anywhere (house rule), so no broad keeps needed.

# OkHttp platform warnings
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
