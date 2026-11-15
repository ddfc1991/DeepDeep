-keep class io.github.whywood.deepdeep.plugin.api.** { *; }
-keep class io.github.whywood.deepdeep.design.** { *; }
-keep class org.lsposed.hiddenapibypass.** { *; }
-keep class com.github.muntashirakon.adb.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Plugin ABI: plugins reuse the host's Kotlin/coroutines/Compose at runtime.
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-keep class androidx.compose.** { *; }
-keep class androidx.lifecycle.** { *; }
-keep class androidx.activity.** { *; }
-keep class androidx.savedstate.** { *; }

-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_common.** { *; }
-dontwarn com.google.mlkit.**

-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Wireless-debug shell daemon is launched via app_process reflection; R8 must keep the entry class and its main.
-keep class io.github.whywood.deepdeep.adb.ShellDaemon { *; }
-keep class io.github.whywood.deepdeep.vscreen.VirtualScreenHelperMain { *; }

# WorkManager ships a prebuilt Room database whose implementation is created by Room via reflection.
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# sherpa-onnx 本地离线识别：JNI 绑定类必须保留 native 方法，数据类供 Kotlin 反射/序列化使用。
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**
