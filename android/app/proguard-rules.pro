# JNI 回调入口（AGENTS.md R5/R6）：Rust Core 经 JNI 按名字查找这些方法，
# 即使当前 minifyEnabled=false，也保留以防开启混淆后回调失效。
-keep class com.yaya.ai.AgentHost { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}