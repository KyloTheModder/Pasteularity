# ByteZuku starts this class by name in a separate app_process.
-keep class win.libpaste.pasteularity.shell.BytezukuShellService { *; }

# libadb-android and Conscrypt find TLS classes by reflection.
-keep class io.github.muntashirakon.adb.** { *; }
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**
-dontwarn com.android.org.conscrypt.**

-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
