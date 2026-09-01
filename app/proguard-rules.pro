# ProGuard / R8 规则（release 开启 minify + shrinkResources）
#
# Compose、OkHttp、Coil、Lifecycle 等运行时依赖反射，R8 默认规则
# 已通过各自 consumer-rules 处理绝大多数情况；以下是项目级补充。

# ---- Kotlin / 协程 ----
# 注:kotlin.Metadata 的保留规则由现代 AGP 默认配置(Kotlin consumer rules)提供,
# 此前手写的 -keepclassmembers class kotlin.Metadata 已是残留,移除以减冗余。
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

# ---- 数据模型（@Parcelize 保留）----
# 项目 JSON 解析一律用 org.json 硬编码 key(不依赖反射读字段名),Room 的 @Entity
# 由 Room consumer rules 自动保留,故此前整包 -keep data.** 已无必要且会让 R8
# 在 data 包完全失效(无法内联/裁剪 Repository、HttpClient 等非数据类)。
# 这里只对 @Parcelize 数据类显式双保险(Parcelize 插件本身已自动生成 keep,
# 加这条是为了规则可见性 + 防插件行为变化)。
-keep @kotlinx.parcelize.Parcelize class com.peng.ainewshub.data.** { *; }

# ---- Glance 小组件 ----
# Glance 自带 consumer rules;Receiver / ActionCallback 经反射实例化,这里双保险
-keep class com.peng.ainewshub.widget.** { *; }
-dontwarn androidx.glance.**

# ---- WorkManager(每日更新通知) ----
# WorkManager 按类名反射实例化 Worker(WorkSpec 存类名),双保险防 R8 混淆/裁剪
-keep class * extends androidx.work.ListenableWorker { *; }
-dontwarn androidx.work.**

# ---- 通用保护 ----
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
