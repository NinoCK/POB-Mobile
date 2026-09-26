# No reflection-based serialization is used; default rules are sufficient.

# JNI (native/pob_jni.c): the native side calls LuaHost methods by name and throws LuaException
-keep interface io.room.poe2tree.engine.LuaHost { *; }
-keepclassmembers class * implements io.room.poe2tree.engine.LuaHost {
    public byte[] readFile(java.lang.String);
    public void log(byte[]);
    public byte[] inflate(byte[]);
    public byte[] deflate(byte[]);
}
-keep class io.room.poe2tree.engine.LuaException { <init>(java.lang.String); }
