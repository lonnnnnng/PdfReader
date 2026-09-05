-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**

# joda-time（readium 依赖）仅在编译期引用 org.joda.convert 注解，运行时不需要
-dontwarn org.joda.convert.**
