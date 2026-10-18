# NanoHTTPD is referenced directly, but keep its public API in case a future
# refactor walks the handler class by name.
-keep class org.nanohttpd.** { *; }
-keep class fi.iki.elonen.** { *; }

# Media3 views (PlayerView & co.) are inflated from XML by name, so they must
# survive R8 even though our code only touches them through the API.
-keep class androidx.media3.ui.** { *; }
-keep class androidx.media3.exoplayer.** { *; }
-dontwarn androidx.media3.**

# Our own UPnP models are plain data classes; keep their names readable in
# crash logs.
-keep class com.mavis.m3u8dlna.dlna.** { *; }
-keep class com.mavis.m3u8dlna.proxy.** { *; }

# ViewModels are created reflectively by ViewModelProvider. AndroidX only ships
# a keep rule for the SavedStateHandle constructor, so a plain no-arg
# constructor would be stripped and the app would crash on launch with
# NoSuchMethodException: <init>().
-keep class com.mavis.m3u8dlna.MainViewModel { <init>(android.app.Application); }
-keepclassmembers,allowobfuscation class * extends androidx.lifecycle.ViewModel {
    <init>();
}

# OkHttp / Okio ship their own consumer rules, but silence the optional
# platform integrations we do not use.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
