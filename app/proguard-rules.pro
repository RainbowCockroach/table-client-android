# R8 runs in full mode (AGP 8 default), so anything reached by reflection rather than by a
# call edge has to be named here. Room, WorkManager, OkHttp and Compose ship their own
# consumer rules; what follows is what those do not cover.

# kotlinx.serialization resolves a @Serializable class's generated serializer reflectively
# through the companion. Full mode discards unused companions, which breaks that lookup.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp compiles against Conscrypt/BouncyCastle/OpenJSSE platforms that are absent at runtime;
# without this R8 reports the missing classes as errors.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Tink (via androidx.security-crypto) references Error Prone's compile-only annotations.
-dontwarn com.google.errorprone.annotations.**

# WorkManager stores the worker's class name in its own database, so a transfer enqueued by
# one release is instantiated by name after the upgrade that runs it. An obfuscated name is
# not stable across builds; renaming this class would strand every queued transfer.
-keepnames class com.rainbowcockroach.table.tableandroidclient.transfer.TransferWorker

# Crash reports from a minified build are unreadable without the line mapping; keeping the
# attributes lets mapping.txt in the release outputs de-obfuscate a stack trace.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
