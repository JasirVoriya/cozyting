# JNI：Rust 侧按 Java_com_miaocast_app_NativeBridge_<方法名> 导出，类名和 native 方法名都不能被混淆/删除
-keep class com.miaocast.app.NativeBridge { *; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
# app_process 调试入口（adb shell app_process ... com.miaocast.app.DevMain）
-keep class com.miaocast.app.DevMain { public static void main(java.lang.String[]); }
# 内置无线调试 app_process 音频采集 Helper 入口（uid=2000）
-keep class com.miaocast.app.capture.shell.ShellAudioHelperMain { public static void main(java.lang.String[]); }
-keep class com.miaocast.app.capture.shell.AudioPolicyReflector { *; }
-keep class com.miaocast.app.capture.shell.ShellAudioFocusPolicyBridge { *; }
-dontwarn android.media.AudioFocusInfo
-dontwarn android.media.audiopolicy.AudioPolicy
-dontwarn android.media.audiopolicy.AudioPolicy$AudioPolicyFocusListener
