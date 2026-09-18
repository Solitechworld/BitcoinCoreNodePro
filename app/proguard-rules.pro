# ---------------------------------------------------------------------------
# Bitcoin Core Node -- R8 configuration
# ---------------------------------------------------------------------------

# kotlinx.serialization: the plugin generates serializers that R8 can't see
# being used. Losing one turns every RPC response into a runtime crash.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.solitech.bitcoincorenode.**$$serializer { *; }
-keepclassmembers class com.solitech.bitcoincorenode.** {
    *** Companion;
}
-keepclasseswithmembers class com.solitech.bitcoincorenode.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Every @Serializable model, kept whole. These mirror Bitcoin Core's RPC
# response shapes; obfuscating their field names breaks deserialization.
-keep @kotlinx.serialization.Serializable class com.solitech.bitcoincorenode.core.model.** { *; }
-keep @kotlinx.serialization.Serializable class com.solitech.bitcoincorenode.core.rpc.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Hilt / Dagger
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# ZXing
-keep class com.google.zxing.** { *; }
-dontwarn com.journeyapps.barcodescanner.**

# Keep the exception types we surface to users -- their simple names appear in
# error copy, and an obfuscated one reads as gibberish in a bug report.
-keep class com.solitech.bitcoincorenode.core.rpc.RpcError { *; }
-keep class com.solitech.bitcoincorenode.core.rpc.RpcError$* { *; }

# Strip all logging from release builds. A node's debug output can contain
# addresses, xpubs and transaction details; none of that belongs in logcat on
# a user's device where any other app with READ_LOGS could see it.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
}
