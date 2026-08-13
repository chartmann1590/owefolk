-keepattributes Signature,*Annotation*,EnclosingMethod
-dontwarn javax.annotation.**

-keep class com.charles.owefolk.domain.Money { *; }
-keep class com.charles.owefolk.domain.MoneyMath { *; }
-keep class com.charles.owefolk.domain.DebtSimplifier { *; }
-keep class com.charles.owefolk.domain.LedgerMath { *; }
-keep class com.charles.owefolk.domain.Models$* { *; }

-keep class kotlinx.serialization.** { *; }
-keep class kotlinx.coroutines.** { *; }

-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.ads.** { *; }
-keep class com.google.android.ump.** { *; }
-keep class com.google.mlkit.** { *; }

-keep class androidx.datastore.** { *; }
-keep class androidx.navigation.** { *; }
-keep class androidx.credentials.** { *; }

-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}

-keepattributes *Annotation*

-dontwarn okhttp3.**
-dontwarn retrofit2.**
-dontwarn com.squareup.okhttp3.**
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

-keep class com.charles.owefolk.** { *; }

-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
