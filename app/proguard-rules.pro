# ProGuard 规则（当前 isMinifyEnabled=false，以下规则供未来启用时参考）

# Kotlin 协程
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class kotlinx.serialization.json.** { *; }
-dontwarn kotlinx.serialization.**

# Room
-keep class * extends androidx.room.RoomDatabase
-keep class dev.dzsun.bookkeeping.core.database.** { *; }
-dontwarn androidx.room.paging.**

# Hilt
-keep class * extends dagger.hilt.android.HiltAndroidApp { *; }
-keep class * implements dagger.hilt.internal.GeneratedComponent { *; }
-keepclassmembers class * {
    @javax.inject.Inject <init>(...);
}

# Compose
-keep class androidx.compose.runtime.ComposerKt { *; }
-keep class androidx.compose.ui.** { *; }
-keep class androidx.compose.material3.** { *; }

# 端侧模型 JNI
-keep class com.arm.aichat.InferenceEngine { *; }
-keep class com.arm.aichat.internal.InferenceEngineImpl { *; }

# JSON 序列化模型（keep 所有 @Serializable 类）
-keep class dev.dzsun.bookkeeping.core.network.** { *; }
-keep class dev.dzsun.bookkeeping.core.statement.** { *; }
-keep class dev.dzsun.bookkeeping.feature.importer.** { *; }
-keep class dev.dzsun.bookkeeping.llm.** { *; }

# 枚举类（Room TypeConverter 用）
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# 资源保留
-keepresources raw/*
-keepresources drawable/*
