# ProGuard rules
# Keep class names for debugging
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# TensorFlow Lite
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.gpu.** { *; }

# OpenCV
-keep class org.bytedeco.opencv.** { *; }
-keep class org.bytedeco.javacpp.** { *; }
