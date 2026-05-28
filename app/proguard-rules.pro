# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in C:\AndroidSDK/tools/proguard/proguard-android.txt
# You can edit the include path and targets by changing the proguardFiles
# directive in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep DynamicsProcessing and Visualizer — used via JNI/reflection
-keep class android.media.audiofx.DynamicsProcessing { *; }
-keep class android.media.audiofx.DynamicsProcessing$** { *; }
-keep class android.media.audiofx.Visualizer { *; }
-keep class android.media.audiofx.Visualizer$** { *; }

# Add any custom ProGuard rules here.
