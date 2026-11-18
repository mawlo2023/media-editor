# ONNX Runtime nutzt JNI – Klassen nicht umbenennen
-keep class ai.onnxruntime.** { *; }
# ML Kit / Play-Services-Tasks
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_segmentation_selfie.** { *; }
-dontwarn com.google.errorprone.annotations.**
