/*
 * JNI bridge between the app (io.room.poe2tree.engine.LuaNative) and LuaJIT, used to run
 * Path of Building's Lua code.
 *
 * A Lua state is driven from a single Java thread. Lua code reaches the host through a few
 * global functions:
 *   __host_loadfile(path)   -> chunk | nil, err   (reads a file of the PoB tree through the host)
 *   __host_readfile(path)   -> string | nil
 *   __host_log(text)
 *   __host_time()           -> milliseconds since an arbitrary point (monotonic)
 *   __host_inflate(data)    -> string | nil       (zlib)
 *   __host_deflate(data)    -> string | nil
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include "lua.h"
#include "lauxlib.h"
#include "lualib.h"

#ifdef _WIN32
#include <windows.h>
#else
#include <time.h>
#endif

typedef struct {
  lua_State *L;
  JNIEnv *env;        /* valid during a call from Java */
  jobject host;       /* global ref to the LuaHost */
  jmethodID readFile;
  jmethodID log;
  jmethodID inflate;
  jmethodID deflate;
} Bridge;

static const char BRIDGE_KEY = 0;

static Bridge *get_bridge(lua_State *L)
{
  Bridge *b;
  lua_pushlightuserdata(L, (void *)&BRIDGE_KEY);
  lua_rawget(L, LUA_REGISTRYINDEX);
  b = (Bridge *)lua_touserdata(L, -1);
  lua_pop(L, 1);
  return b;
}

/* Raises a Lua error for a pending Java exception. */
static int java_error(lua_State *L, Bridge *b, const char *what)
{
  jthrowable ex = (*b->env)->ExceptionOccurred(b->env);
  (*b->env)->ExceptionClear(b->env);
  if (ex) {
    jclass cls = (*b->env)->FindClass(b->env, "java/lang/Object");
    jmethodID toString = (*b->env)->GetMethodID(b->env, cls, "toString", "()Ljava/lang/String;");
    jstring s = (jstring)(*b->env)->CallObjectMethod(b->env, ex, toString);
    (*b->env)->ExceptionClear(b->env);
    if (s) {
      const char *c = (*b->env)->GetStringUTFChars(b->env, s, NULL);
      lua_pushfstring(L, "%s: %s", what, c);
      (*b->env)->ReleaseStringUTFChars(b->env, s, c);
      return lua_error(L);
    }
  }
  return luaL_error(L, "%s: Java exception", what);
}

static jbyteArray to_bytes(JNIEnv *env, const char *s, size_t len)
{
  jbyteArray arr = (*env)->NewByteArray(env, (jsize)len);
  if (arr && len) (*env)->SetByteArrayRegion(env, arr, 0, (jsize)len, (const jbyte *)s);
  return arr;
}

/* Calls host.method(bytes) -> byte[] and pushes the result as a string (or nil). */
static int call_bytes(lua_State *L, jmethodID method, const char *what, int isPath)
{
  Bridge *b = get_bridge(L);
  size_t len;
  const char *s = luaL_checklstring(L, 1, &len);
  jobject arg;
  jbyteArray res;
  if (isPath) {
    arg = (*b->env)->NewStringUTF(b->env, s);
  } else {
    arg = to_bytes(b->env, s, len);
  }
  if (!arg) return java_error(L, b, what);
  res = (jbyteArray)(*b->env)->CallObjectMethod(b->env, b->host, method, arg);
  (*b->env)->DeleteLocalRef(b->env, arg);
  if ((*b->env)->ExceptionCheck(b->env)) return java_error(L, b, what);
  if (!res) {
    lua_pushnil(L);
    return 1;
  }
  {
    jsize n = (*b->env)->GetArrayLength(b->env, res);
    jbyte *data = (*b->env)->GetPrimitiveArrayCritical(b->env, res, NULL);
    lua_pushlstring(L, (const char *)data, (size_t)n);
    (*b->env)->ReleasePrimitiveArrayCritical(b->env, res, data, JNI_ABORT);
    (*b->env)->DeleteLocalRef(b->env, res);
  }
  return 1;
}

static int host_readfile(lua_State *L)
{
  return call_bytes(L, get_bridge(L)->readFile, "readfile", 1);
}

static int host_loadfile(lua_State *L)
{
  Bridge *b = get_bridge(L);
  const char *path = luaL_checkstring(L, 1);
  jstring jpath = (*b->env)->NewStringUTF(b->env, path);
  jbyteArray res;
  int status;
  if (!jpath) return java_error(L, b, "loadfile");
  res = (jbyteArray)(*b->env)->CallObjectMethod(b->env, b->host, b->readFile, jpath);
  (*b->env)->DeleteLocalRef(b->env, jpath);
  if ((*b->env)->ExceptionCheck(b->env)) return java_error(L, b, "loadfile");
  if (!res) {
    lua_pushnil(L);
    lua_pushfstring(L, "cannot open %s", path);
    return 2;
  }
  lua_pushfstring(L, "@%s", path);
  {
    jsize n = (*b->env)->GetArrayLength(b->env, res);
    jbyte *data = (*b->env)->GetByteArrayElements(b->env, res, NULL);
    status = luaL_loadbuffer(L, (const char *)data, (size_t)n, lua_tostring(L, -1));
    (*b->env)->ReleaseByteArrayElements(b->env, res, data, JNI_ABORT);
    (*b->env)->DeleteLocalRef(b->env, res);
  }
  lua_remove(L, -2); /* chunk name */
  if (status != 0) {
    lua_pushnil(L);
    lua_insert(L, -2);
    return 2;
  }
  return 1;
}

static int host_log(lua_State *L)
{
  Bridge *b = get_bridge(L);
  size_t len;
  const char *s;
  jbyteArray arr;
  lua_getglobal(L, "tostring");
  lua_pushvalue(L, 1);
  lua_call(L, 1, 1);
  s = lua_tolstring(L, -1, &len);
  arr = to_bytes(b->env, s ? s : "", s ? len : 0);
  if (!arr) return java_error(L, b, "log");
  (*b->env)->CallVoidMethod(b->env, b->host, b->log, arr);
  (*b->env)->DeleteLocalRef(b->env, arr);
  if ((*b->env)->ExceptionCheck(b->env)) return java_error(L, b, "log");
  return 0;
}

static int host_time(lua_State *L)
{
#ifdef _WIN32
  lua_pushnumber(L, (lua_Number)GetTickCount64());
#else
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  lua_pushnumber(L, (lua_Number)ts.tv_sec * 1000.0 + (lua_Number)(ts.tv_nsec / 1000000));
#endif
  return 1;
}

static int host_inflate(lua_State *L)
{
  return call_bytes(L, get_bridge(L)->inflate, "inflate", 0);
}

static int host_deflate(lua_State *L)
{
  return call_bytes(L, get_bridge(L)->deflate, "deflate", 0);
}

/* Error handler: message plus traceback. */
static int traceback(lua_State *L)
{
  const char *msg = lua_tostring(L, 1);
  if (msg == NULL) {
    if (luaL_callmeta(L, 1, "__tostring") && lua_type(L, -1) == LUA_TSTRING) return 1;
    msg = lua_pushfstring(L, "(error object is a %s value)", luaL_typename(L, 1));
  }
  luaL_traceback(L, L, msg, 1);
  return 1;
}

static void throw_java(JNIEnv *env, const char *cls, const char *msg)
{
  jclass c = (*env)->FindClass(env, cls);
  if (c) (*env)->ThrowNew(env, c, msg);
}

#define BRIDGE(ptr) ((Bridge *)(intptr_t)(ptr))

JNIEXPORT jlong JNICALL Java_io_room_poe2tree_engine_LuaNative_create(JNIEnv *env, jclass cls, jobject host)
{
  Bridge *b = (Bridge *)calloc(1, sizeof(Bridge));
  jclass hostCls;
  (void)cls;
  if (!b) {
    throw_java(env, "java/lang/OutOfMemoryError", "bridge");
    return 0;
  }
  b->L = luaL_newstate();
  if (!b->L) {
    free(b);
    throw_java(env, "java/lang/OutOfMemoryError", "lua_State");
    return 0;
  }
  b->env = env;
  b->host = (*env)->NewGlobalRef(env, host);
  hostCls = (*env)->GetObjectClass(env, host);
  b->readFile = (*env)->GetMethodID(env, hostCls, "readFile", "(Ljava/lang/String;)[B");
  b->log = (*env)->GetMethodID(env, hostCls, "log", "([B)V");
  b->inflate = (*env)->GetMethodID(env, hostCls, "inflate", "([B)[B");
  b->deflate = (*env)->GetMethodID(env, hostCls, "deflate", "([B)[B");
  if ((*env)->ExceptionCheck(env)) {
    lua_close(b->L);
    (*env)->DeleteGlobalRef(env, b->host);
    free(b);
    return 0;
  }
  luaL_openlibs(b->L);
  lua_pushlightuserdata(b->L, (void *)&BRIDGE_KEY);
  lua_pushlightuserdata(b->L, b);
  lua_rawset(b->L, LUA_REGISTRYINDEX);
  lua_register(b->L, "__host_loadfile", host_loadfile);
  lua_register(b->L, "__host_readfile", host_readfile);
  lua_register(b->L, "__host_log", host_log);
  lua_register(b->L, "__host_time", host_time);
  lua_register(b->L, "__host_inflate", host_inflate);
  lua_register(b->L, "__host_deflate", host_deflate);
  return (jlong)(intptr_t)b;
}

/*
 * Runs a chunk. Returns the chunk's first result converted to a string (null for nil / no result).
 * Lua errors are thrown as io.room.poe2tree.engine.LuaException with the traceback.
 */
JNIEXPORT jbyteArray JNICALL Java_io_room_poe2tree_engine_LuaNative_exec(JNIEnv *env, jclass cls, jlong ptr, jbyteArray code, jstring chunkName)
{
  Bridge *b = BRIDGE(ptr);
  lua_State *L = b->L;
  int base = lua_gettop(L);
  const char *name = (*env)->GetStringUTFChars(env, chunkName, NULL);
  jsize n = (*env)->GetArrayLength(env, code);
  jbyte *data = (*env)->GetByteArrayElements(env, code, NULL);
  int status;
  jbyteArray result = NULL;
  (void)cls;
  b->env = env;
  lua_pushcfunction(L, traceback);
  status = luaL_loadbuffer(L, (const char *)data, (size_t)n, name);
  (*env)->ReleaseByteArrayElements(env, code, data, JNI_ABORT);
  (*env)->ReleaseStringUTFChars(env, chunkName, name);
  if (status == 0) status = lua_pcall(L, 0, 1, base + 1);
  if (status != 0) {
    const char *msg = lua_tostring(L, -1);
    throw_java(env, "io/room/poe2tree/engine/LuaException", msg ? msg : "unknown Lua error");
  } else if (!lua_isnil(L, -1)) {
    size_t len;
    const char *s = lua_tolstring(L, -1, &len);
    if (s) result = to_bytes(env, s, len);
  }
  lua_settop(L, base);
  return result;
}

JNIEXPORT jlong JNICALL Java_io_room_poe2tree_engine_LuaNative_memoryKb(JNIEnv *env, jclass cls, jlong ptr)
{
  (void)env;
  (void)cls;
  return (jlong)lua_gc(BRIDGE(ptr)->L, LUA_GCCOUNT, 0);
}

JNIEXPORT void JNICALL Java_io_room_poe2tree_engine_LuaNative_close(JNIEnv *env, jclass cls, jlong ptr)
{
  Bridge *b = BRIDGE(ptr);
  (void)cls;
  if (!b) return;
  b->env = env;
  lua_close(b->L);
  (*env)->DeleteGlobalRef(env, b->host);
  free(b);
}
