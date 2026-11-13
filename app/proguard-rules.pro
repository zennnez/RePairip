# ProGuard rules for RePairip LSPosed module (LibXposed API 102)

# Keep module entry point and hooker
-keep class com.repairip.module.** { *; }

# Modern LibXposed rules
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

# DexKit
-keep class org.luckypray.dexkit.** { *; }
-dontwarn org.luckypray.dexkit.**

# General Android
-dontwarn android.**
