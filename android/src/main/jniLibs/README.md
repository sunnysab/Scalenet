# Native libraries

This app loads an additional native library from footprint (`libfootprint_ffi.so`) at runtime.

Place the prebuilt `libfootprint_ffi.so` for each ABI under:

- `android/src/main/jniLibs/arm64-v8a/libfootprint_ffi.so`
- `android/src/main/jniLibs/armeabi-v7a/libfootprint_ffi.so`
- `android/src/main/jniLibs/x86_64/libfootprint_ffi.so`

The JNI glue library (`libfootprintjni.so`) is built by Gradle (CMake) and will `dlopen()` the
footprint FFI library.

