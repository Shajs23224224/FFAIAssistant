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

# SocketIO
-keep class io.socket.** { *; }
-keep class io.socket.engineio.** { *; }
-keep class io.socket.client.** { *; }
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions
-dontwarn io.socket.**

# OkHttp (SocketIO dependency)
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
