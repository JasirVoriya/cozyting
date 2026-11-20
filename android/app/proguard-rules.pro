# JNI：Rust 侧按 Java_com_miplay_sender_NativeBridge_<方法名> 导出，类名和 native 方法名都不能被混淆/删除
-keep class com.miplay.sender.NativeBridge { *; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
# app_process 调试入口（adb shell app_process ... com.miplay.sender.DevMain）
-keep class com.miplay.sender.DevMain { public static void main(java.lang.String[]); }
