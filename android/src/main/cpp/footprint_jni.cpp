#include <jni.h>

#include <android/log.h>
#include <dlfcn.h>
#include <stdint.h>
#include <string.h>

#include <mutex>
#include <string>

namespace {

constexpr const char* kTag = "FootprintJNI";
constexpr uint32_t kAbiVersion = 1;

struct fp_engine_t;

typedef int (*fp_protect_cb)(void* ctx, int fd);
typedef void (*fp_log_cb)(void* ctx, int level, const char* msg, size_t msg_len);

struct fp_callbacks_t {
  uint32_t abi_version;
  uint32_t size;
  fp_protect_cb protect;
  void* protect_ctx;
  fp_log_cb log;
  void* log_ctx;
};

struct fp_bytes_t {
  const uint8_t* ptr;
  size_t len;
};

struct fp_buf_t {
  uint8_t* ptr;
  size_t len;
};

enum fp_route_action_t : int32_t {
  FP_ROUTE_UNKNOWN = 0,
  FP_ROUTE_DIRECT = 1,
  FP_ROUTE_PROXY = 2,
};

struct fp_dial_req_t {
  uint32_t abi_version;
  uint32_t size;

  uint32_t dst_ip4_be;
  uint16_t dst_port_be;
  uint16_t _reserved0;

  const char* hostname;
  size_t hostname_len;

  const char* route_profile;
  size_t route_profile_len;

  uint32_t timeout_ms;
  uint32_t flags;
};

struct fp_dial_res_t {
  int32_t fd;
  fp_route_action_t action;
  uint32_t err_code;
  fp_buf_t err_msg;
  fp_buf_t debug_json;
};

typedef fp_engine_t* (*fp_engine_new_fn)(const fp_callbacks_t* callbacks, fp_buf_t* out_err);
typedef int (*fp_engine_set_config_toml_fn)(fp_engine_t* e, fp_bytes_t toml, fp_buf_t* out_err);
typedef int (*fp_engine_set_route_profile_fn)(fp_engine_t* e, const char* profile, size_t profile_len, fp_buf_t* out_err);
typedef void (*fp_engine_free_fn)(fp_engine_t* e);
typedef void (*fp_buf_free_fn)(fp_buf_t buf);
typedef fp_dial_res_t (*fp_engine_dial_tcp_v4_fn)(fp_engine_t* e, const fp_dial_req_t* req);

struct FootprintApi {
  void* handle = nullptr;

  fp_engine_new_fn engine_new = nullptr;
  fp_engine_set_config_toml_fn engine_set_config_toml = nullptr;
  fp_engine_set_route_profile_fn engine_set_route_profile = nullptr;
  fp_engine_dial_tcp_v4_fn engine_dial_tcp_v4 = nullptr;
  fp_engine_free_fn engine_free = nullptr;
  fp_buf_free_fn buf_free = nullptr;
};

std::mutex g_api_mu;
FootprintApi g_api;

JavaVM* g_vm = nullptr;

jclass g_dial_result_class = nullptr;
jmethodID g_dial_result_ctor = nullptr;

jclass g_protector_class = nullptr;
jmethodID g_protector_protect_mid = nullptr;

jclass g_logger_class = nullptr;
jmethodID g_logger_log_mid = nullptr;

struct EngineCtx {
  jobject protector = nullptr;  // GlobalRef or null
  jobject logger = nullptr;     // GlobalRef or null
};

struct EngineHandle {
  fp_engine_t* engine = nullptr;
  EngineCtx* ctx = nullptr;
};

static void loge(const char* msg) {
  __android_log_print(ANDROID_LOG_ERROR, kTag, "%s", msg);
}

static void loge_str(const std::string& msg) {
  __android_log_print(ANDROID_LOG_ERROR, kTag, "%s", msg.c_str());
}

static std::string dlerror_string() {
  const char* err = dlerror();
  if (!err) return "unknown dlerror";
  return std::string(err);
}

static bool ensure_api_loaded(std::string* out_err) {
  std::lock_guard<std::mutex> lock(g_api_mu);
  if (g_api.handle != nullptr) {
    return true;
  }

  // Ensure clean dlerror state.
  dlerror();
  void* h = dlopen("libfootprint_ffi.so", RTLD_NOW | RTLD_LOCAL);
  if (!h) {
    *out_err = "dlopen libfootprint_ffi.so failed: " + dlerror_string();
    return false;
  }

  auto sym = [&](const char* name) -> void* {
    dlerror();
    void* p = dlsym(h, name);
    return p;
  };

  g_api.engine_new = reinterpret_cast<fp_engine_new_fn>(sym("fp_engine_new"));
  g_api.engine_set_config_toml = reinterpret_cast<fp_engine_set_config_toml_fn>(sym("fp_engine_set_config_toml"));
  g_api.engine_set_route_profile = reinterpret_cast<fp_engine_set_route_profile_fn>(sym("fp_engine_set_route_profile"));
  g_api.engine_dial_tcp_v4 = reinterpret_cast<fp_engine_dial_tcp_v4_fn>(sym("fp_engine_dial_tcp_v4"));
  g_api.engine_free = reinterpret_cast<fp_engine_free_fn>(sym("fp_engine_free"));
  g_api.buf_free = reinterpret_cast<fp_buf_free_fn>(sym("fp_buf_free"));

  if (!g_api.engine_new || !g_api.engine_set_config_toml || !g_api.engine_set_route_profile ||
      !g_api.engine_dial_tcp_v4 || !g_api.engine_free || !g_api.buf_free) {
    *out_err = "dlsym footprint_ffi missing symbol(s): " + dlerror_string();
    dlclose(h);
    memset(&g_api, 0, sizeof(g_api));
    return false;
  }

  g_api.handle = h;
  return true;
}

static jbyteArray to_byte_array(JNIEnv* env, const uint8_t* ptr, size_t len) {
  if (!ptr || len == 0) return nullptr;
  if (len > static_cast<size_t>(INT32_MAX)) return nullptr;
  jbyteArray arr = env->NewByteArray(static_cast<jsize>(len));
  if (!arr) return nullptr;
  env->SetByteArrayRegion(arr, 0, static_cast<jsize>(len), reinterpret_cast<const jbyte*>(ptr));
  return arr;
}

static int protect_cb(void* ctx, int fd) {
  EngineCtx* ectx = reinterpret_cast<EngineCtx*>(ctx);
  if (!ectx || !ectx->protector) {
    return 0;
  }

  JNIEnv* env = nullptr;
  bool did_attach = false;
  const jint get_env_rc = g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
  if (get_env_rc == JNI_EDETACHED) {
    if (g_vm->AttachCurrentThread(&env, nullptr) != 0) {
      return -1;
    }
    did_attach = true;
  } else if (get_env_rc != JNI_OK) {
    return -1;
  }

  jint rc = env->CallIntMethod(ectx->protector, g_protector_protect_mid, static_cast<jint>(fd));
  if (env->ExceptionCheck()) {
    env->ExceptionClear();
    rc = -1;
  }

  if (did_attach) {
    g_vm->DetachCurrentThread();
  }
  return rc;
}

static void log_cb(void* ctx, int level, const char* msg, size_t msg_len) {
  EngineCtx* ectx = reinterpret_cast<EngineCtx*>(ctx);
  if (!ectx || !ectx->logger || !msg || msg_len == 0) {
    return;
  }

  JNIEnv* env = nullptr;
  bool did_attach = false;
  const jint get_env_rc = g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
  if (get_env_rc == JNI_EDETACHED) {
    if (g_vm->AttachCurrentThread(&env, nullptr) != 0) {
      return;
    }
    did_attach = true;
  } else if (get_env_rc != JNI_OK) {
    return;
  }

  jbyteArray bytes = to_byte_array(env, reinterpret_cast<const uint8_t*>(msg), msg_len);
  if (bytes) {
    env->CallVoidMethod(ectx->logger, g_logger_log_mid, static_cast<jint>(level), bytes);
    if (env->ExceptionCheck()) {
      env->ExceptionClear();
    }
    env->DeleteLocalRef(bytes);
  }

  if (did_attach) {
    g_vm->DetachCurrentThread();
  }
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_cn_sunnysab_scalenet_proxy_FootprintNative_nativeCreateEngine(
    JNIEnv* env, jclass, jobject protector, jobject logger, jobjectArray outErr) {
  std::string err;
  if (!ensure_api_loaded(&err)) {
    loge_str(err);
    if (outErr && env->GetArrayLength(outErr) > 0) {
      jbyteArray b = to_byte_array(env, reinterpret_cast<const uint8_t*>(err.data()), err.size());
      env->SetObjectArrayElement(outErr, 0, b);
      if (b) env->DeleteLocalRef(b);
    }
    return 0;
  }

  auto* ctx = new EngineCtx();
  if (protector) ctx->protector = env->NewGlobalRef(protector);
  if (logger) ctx->logger = env->NewGlobalRef(logger);

  fp_callbacks_t cbs{};
  cbs.abi_version = kAbiVersion;
  cbs.size = sizeof(fp_callbacks_t);
  if (ctx->protector) {
    cbs.protect = protect_cb;
    cbs.protect_ctx = ctx;
  }
  if (ctx->logger) {
    cbs.log = log_cb;
    cbs.log_ctx = ctx;
  }

  fp_buf_t out_err_buf{};
  fp_engine_t* engine = g_api.engine_new(&cbs, &out_err_buf);
  if (!engine) {
    std::string emsg = "fp_engine_new failed";
    if (out_err_buf.ptr && out_err_buf.len) {
      emsg.assign(reinterpret_cast<const char*>(out_err_buf.ptr), out_err_buf.len);
      g_api.buf_free(out_err_buf);
    }
    if (outErr && env->GetArrayLength(outErr) > 0) {
      jbyteArray b = to_byte_array(env, reinterpret_cast<const uint8_t*>(emsg.data()), emsg.size());
      env->SetObjectArrayElement(outErr, 0, b);
      if (b) env->DeleteLocalRef(b);
    }
    if (ctx->protector) env->DeleteGlobalRef(ctx->protector);
    if (ctx->logger) env->DeleteGlobalRef(ctx->logger);
    delete ctx;
    return 0;
  }

  auto* h = new EngineHandle();
  h->engine = engine;
  h->ctx = ctx;
  return reinterpret_cast<jlong>(h);
}

extern "C" JNIEXPORT void JNICALL
Java_cn_sunnysab_scalenet_proxy_FootprintNative_nativeFreeEngine(JNIEnv* env, jclass, jlong handle) {
  auto* h = reinterpret_cast<EngineHandle*>(handle);
  if (!h) return;
  if (h->engine) {
    std::string err;
    if (ensure_api_loaded(&err)) {
      g_api.engine_free(h->engine);
    }
  }
  if (h->ctx) {
    if (h->ctx->protector) env->DeleteGlobalRef(h->ctx->protector);
    if (h->ctx->logger) env->DeleteGlobalRef(h->ctx->logger);
    delete h->ctx;
  }
  delete h;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_cn_sunnysab_scalenet_proxy_FootprintNative_nativeSetConfigToml(
    JNIEnv* env, jclass, jlong handle, jbyteArray tomlBytes) {
  auto* h = reinterpret_cast<EngineHandle*>(handle);
  if (!h || !h->engine) {
    const char* msg = "engine handle is null";
    return to_byte_array(env, reinterpret_cast<const uint8_t*>(msg), strlen(msg));
  }

  std::string err;
  if (!ensure_api_loaded(&err)) {
    return to_byte_array(env, reinterpret_cast<const uint8_t*>(err.data()), err.size());
  }

  jbyte* bytes = env->GetByteArrayElements(tomlBytes, nullptr);
  jsize len = env->GetArrayLength(tomlBytes);
  fp_bytes_t toml{reinterpret_cast<const uint8_t*>(bytes), static_cast<size_t>(len)};
  fp_buf_t out_err{};
  int rc = g_api.engine_set_config_toml(h->engine, toml, &out_err);
  env->ReleaseByteArrayElements(tomlBytes, bytes, JNI_ABORT);

  if (rc == 0) {
    if (out_err.ptr && out_err.len) {
      g_api.buf_free(out_err);
    }
    return nullptr;
  }

  jbyteArray res = to_byte_array(env, out_err.ptr, out_err.len);
  g_api.buf_free(out_err);
  return res;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_cn_sunnysab_scalenet_proxy_FootprintNative_nativeSetRouteProfile(
    JNIEnv* env, jclass, jlong handle, jstring profile) {
  auto* h = reinterpret_cast<EngineHandle*>(handle);
  if (!h || !h->engine) {
    const char* msg = "engine handle is null";
    return to_byte_array(env, reinterpret_cast<const uint8_t*>(msg), strlen(msg));
  }

  std::string err;
  if (!ensure_api_loaded(&err)) {
    return to_byte_array(env, reinterpret_cast<const uint8_t*>(err.data()), err.size());
  }

  const char* utf = profile ? env->GetStringUTFChars(profile, nullptr) : nullptr;
  const size_t utf_len = profile ? static_cast<size_t>(env->GetStringUTFLength(profile)) : 0;

  fp_buf_t out_err{};
  int rc = g_api.engine_set_route_profile(h->engine, utf ? utf : "", utf ? utf_len : 0, &out_err);
  if (profile && utf) env->ReleaseStringUTFChars(profile, utf);

  if (rc == 0) {
    if (out_err.ptr && out_err.len) {
      g_api.buf_free(out_err);
    }
    return nullptr;
  }
  jbyteArray res = to_byte_array(env, out_err.ptr, out_err.len);
  g_api.buf_free(out_err);
  return res;
}

extern "C" JNIEXPORT jobject JNICALL
Java_cn_sunnysab_scalenet_proxy_FootprintNative_nativeDialTcpV4(
    JNIEnv* env,
    jclass,
    jlong handle,
    jint dstIp4Be,
    jint dstPortBe,
    jstring hostname,
    jstring routeProfile,
    jint timeoutMs,
    jint flags) {
  auto* h = reinterpret_cast<EngineHandle*>(handle);
  if (!h || !h->engine) {
    return env->NewObject(g_dial_result_class, g_dial_result_ctor, -1, 0, 1,
                          nullptr, nullptr);
  }

  std::string err;
  if (!ensure_api_loaded(&err)) {
    jbyteArray ebytes = to_byte_array(env, reinterpret_cast<const uint8_t*>(err.data()), err.size());
    jobject obj = env->NewObject(g_dial_result_class, g_dial_result_ctor, -1, 0, 8, ebytes, nullptr);
    if (ebytes) env->DeleteLocalRef(ebytes);
    return obj;
  }

  const char* host_utf = hostname ? env->GetStringUTFChars(hostname, nullptr) : nullptr;
  const size_t host_len = hostname ? static_cast<size_t>(env->GetStringUTFLength(hostname)) : 0;
  const char* profile_utf = routeProfile ? env->GetStringUTFChars(routeProfile, nullptr) : nullptr;
  const size_t profile_len = routeProfile ? static_cast<size_t>(env->GetStringUTFLength(routeProfile)) : 0;

  fp_dial_req_t req{};
  req.abi_version = kAbiVersion;
  req.size = sizeof(fp_dial_req_t);
  req.dst_ip4_be = static_cast<uint32_t>(dstIp4Be);
  req.dst_port_be = static_cast<uint16_t>(dstPortBe);
  req.hostname = host_utf;
  req.hostname_len = host_len;
  req.route_profile = profile_utf;
  req.route_profile_len = profile_len;
  req.timeout_ms = static_cast<uint32_t>(timeoutMs);
  req.flags = static_cast<uint32_t>(flags);

  fp_dial_res_t res = g_api.engine_dial_tcp_v4(h->engine, &req);

  if (hostname && host_utf) env->ReleaseStringUTFChars(hostname, host_utf);
  if (routeProfile && profile_utf) env->ReleaseStringUTFChars(routeProfile, profile_utf);

  jbyteArray err_bytes = nullptr;
  jbyteArray debug_bytes = nullptr;
  if (res.err_msg.ptr && res.err_msg.len) {
    err_bytes = to_byte_array(env, res.err_msg.ptr, res.err_msg.len);
    g_api.buf_free(res.err_msg);
  }
  if (res.debug_json.ptr && res.debug_json.len) {
    debug_bytes = to_byte_array(env, res.debug_json.ptr, res.debug_json.len);
    g_api.buf_free(res.debug_json);
  }

  jobject obj = env->NewObject(g_dial_result_class, g_dial_result_ctor,
                               static_cast<jint>(res.fd),
                               static_cast<jint>(res.action),
                               static_cast<jint>(res.err_code),
                               err_bytes, debug_bytes);
  if (err_bytes) env->DeleteLocalRef(err_bytes);
  if (debug_bytes) env->DeleteLocalRef(debug_bytes);
  return obj;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
  g_vm = vm;
  JNIEnv* env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
    return JNI_ERR;
  }

  jclass dial_cls_local = env->FindClass("cn/sunnysab/scalenet/proxy/FootprintNative$DialResult");
  if (!dial_cls_local) {
    loge("FindClass DialResult failed");
    return JNI_ERR;
  }
  g_dial_result_class = reinterpret_cast<jclass>(env->NewGlobalRef(dial_cls_local));
  env->DeleteLocalRef(dial_cls_local);
  g_dial_result_ctor = env->GetMethodID(g_dial_result_class, "<init>", "(III[B[B)V");
  if (!g_dial_result_ctor) {
    loge("GetMethodID DialResult.<init> failed");
    return JNI_ERR;
  }

  jclass protector_local = env->FindClass("cn/sunnysab/scalenet/proxy/FootprintSocketProtector");
  if (!protector_local) {
    loge("FindClass FootprintSocketProtector failed");
    return JNI_ERR;
  }
  g_protector_class = reinterpret_cast<jclass>(env->NewGlobalRef(protector_local));
  env->DeleteLocalRef(protector_local);
  g_protector_protect_mid = env->GetMethodID(g_protector_class, "protect", "(I)I");
  if (!g_protector_protect_mid) {
    loge("GetMethodID FootprintSocketProtector.protect failed");
    return JNI_ERR;
  }

  jclass logger_local = env->FindClass("cn/sunnysab/scalenet/proxy/FootprintLogSink");
  if (!logger_local) {
    loge("FindClass FootprintLogSink failed");
    return JNI_ERR;
  }
  g_logger_class = reinterpret_cast<jclass>(env->NewGlobalRef(logger_local));
  env->DeleteLocalRef(logger_local);
  g_logger_log_mid = env->GetMethodID(g_logger_class, "log", "(I[B)V");
  if (!g_logger_log_mid) {
    loge("GetMethodID FootprintLogSink.log failed");
    return JNI_ERR;
  }

  return JNI_VERSION_1_6;
}
