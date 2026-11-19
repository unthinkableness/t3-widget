# OkHttp, Glance, WorkManager and DataStore ship their own consumer rules.
# Keep line numbers so crash logs from the minified build stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
