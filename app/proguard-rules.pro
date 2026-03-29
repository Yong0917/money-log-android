# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# WebView JavaScript Interface 보호 — R8이 @JavascriptInterface 메서드를 제거하지 않도록
-keepclassmembers class com.moneylogs.app.MainActivity$AndroidBridge {
    public *;
}

# 크래시 리포트 스택 트레이스 가독성 유지
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile