-if class keiyoushi.webview.internal.WebViewGlueBridge -keep interface org.chromium.support_lib_boundary.** { *; }

-dontwarn androidx.webkit.**
-dontwarn org.chromium.support_lib_boundary.**
