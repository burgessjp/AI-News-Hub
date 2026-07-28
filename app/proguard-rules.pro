# ProGuard / R8 规则（release 开启 minify + shrinkResources）
#
# Compose、OkHttp、Coil、Lifecycle 等运行时依赖反射，R8 默认规则
# 已通过各自 consumer-rules 处理绝大多数情况；以下是项目级补充。

# ---- Kotlin / 协程 ----
-keepclassmembers class kotlin.Metadata { *; }
-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.android.** { *; }

# ---- Compose ----
# Compose 官方构件自带 consumer rules,无需项目级 keep(整包 keep 会让 R8 完全失效)
-dontwarn androidx.compose.**

# ---- OkHttp / Okio ----
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**

# ---- Coil (图片加载) ----
# 直接调用无反射,无需 keep
-dontwarn coil.**

# ---- jsoup (HTML 解析,解析 github.com/trending) ----
-dontwarn org.jsoup.**

# ---- 数据模型（反序列化 / 序列化保留字段名）----
-keep class com.peng.ainewshub.data.** { *; }
-keepclassmembers class com.peng.ainewshub.data.** { *; }

# ---- 通用保护 ----
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
