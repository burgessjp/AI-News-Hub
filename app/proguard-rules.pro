# ProGuard / R8 规则（release 开启 minify + shrinkResources）
#
# Compose、OkHttp、Coil、Lifecycle 等运行时依赖反射，R8 默认规则
# 已通过各自 consumer-rules 处理绝大多数情况；以下是项目级补充。

# ---- Kotlin / 协程 ----
-keepclassmembers class kotlin.Metadata { *; }
-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.android.** { *; }

# ---- Compose ----
# Compose 编译器生成的代码大量使用反射，保留运行时入口
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.** { *; }
-keep class androidx.compose.material3.** { *; }
-keep class androidx.compose.material.icons.** { *; }
-keep @androidx.compose.runtime.Immutable class * { *; }
-keep @androidx.compose.runtime.Stable class * { *; }
-dontwarn androidx.compose.**

# ---- OkHttp / Okio ----
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**

# ---- Coil (图片加载，反射读 Model) ----
-keep class coil.** { *; }
-dontwarn coil.**

# ---- jsoup (HTML 解析,解析 github.com/trending) ----
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# ---- Lifecycle / ViewModel ----
-keep class * extends androidx.lifecycle.ViewModel { *; }
-keep class * extends androidx.lifecycle.AndroidViewModel { *; }
-keep class androidx.lifecycle.** { *; }

# ---- 数据模型（反序列化 / 序列化保留字段名）----
-keep class com.example.aihot.data.** { *; }
-keepclassmembers class com.example.aihot.data.** { *; }

# ---- 通用保护 ----
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
