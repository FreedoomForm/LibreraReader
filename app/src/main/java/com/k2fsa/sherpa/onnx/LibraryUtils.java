package com.k2fsa.sherpa.onnx;

// Android flavor: the native libraries ship inside the APK (jniLibs),
// so a plain System.loadLibrary is enough. libonnxruntime.so is resolved
// automatically through DT_NEEDED.
public class LibraryUtils {
    public static void enableDebug() {
    }

    public static void disableDebug() {
    }

    public static void load() {
        System.loadLibrary("sherpa-onnx-jni");
    }
}
