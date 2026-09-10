# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ============================================================
# JNI / Rust 互操作（启用 isMinifyEnabled = true 后必需）
# ============================================================
# app/src/main/rust 中的 Rust 代码通过 FindClass / GetMethodID 使用硬编码的
# 类名与签名（例如 "moe/fuqiuluo/mamu/driver/DisassemblyResult"）。
# 这些类一旦被 R8 重命名或裁剪，运行时会抛 ClassNotFoundException / NoSuchMethodError。

# native 方法宿主类
-keep class moe.fuqiuluo.mamu.MamuApplication { *; }
-keep class moe.fuqiuluo.mamu.driver.WuwaDriver { *; }
-keep class moe.fuqiuluo.mamu.driver.FreezeManager { *; }
-keep class moe.fuqiuluo.mamu.driver.LocalMemoryOps { *; }
-keep class moe.fuqiuluo.mamu.driver.PointerScanner { *; }
-keep class moe.fuqiuluo.mamu.driver.SearchEngine { *; }
-keep class moe.fuqiuluo.mamu.driver.Disassembler { *; }

# native 侧通过 FindClass 构造 / 填充的结果类
-keep class moe.fuqiuluo.mamu.driver.CProcInfo { *; }
-keep class moe.fuqiuluo.mamu.driver.MemRegionEntry { *; }
-keep class moe.fuqiuluo.mamu.driver.DisassemblyResult { *; }
-keep class moe.fuqiuluo.mamu.driver.SearchResultItem { *; }
-keep class moe.fuqiuluo.mamu.driver.ExactSearchResultItem { *; }
-keep class moe.fuqiuluo.mamu.driver.FuzzySearchResultItem { *; }
-keep class moe.fuqiuluo.mamu.driver.PointerChainResult { *; }
-keep class moe.fuqiuluo.mamu.driver.SearchProgressCallback { *; }
-keep class moe.fuqiuluo.mamu.data.model.DriverInfo { *; }
-keep class moe.fuqiuluo.mamu.data.model.DriverInstallResult { *; }
-keep class moe.fuqiuluo.mamu.data.local.RootFileSystem { *; }

# ============================================================
# 三方库
# ============================================================
# libsu：native 侧通过 JNI 反射调用 com.topjohnwu.superuser.Shell
-keep class com.topjohnwu.superuser.** { *; }

# MMKV / LuaJ：内部依赖自身类名与反射保持稳定
-keep class com.tencent.mmkv.** { *; }
-keep class org.luaj.vm2.** { *; }
