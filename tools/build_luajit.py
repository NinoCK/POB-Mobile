"""
Generates the LuaJIT sources that depend on the target CPU, for the Android build and for the
Windows JVM unit tests.

LuaJIT (native/luajit, pinned to the commit Path of Building ships: 2.1.1784580905) needs a few
files produced by its host tools (minilua, buildvm) for each target architecture: the interpreter
in assembly (lj_vm.S) and generated headers. Its Makefile cannot cross-compile to Android from a
Windows host, so this script does the same steps with MSVC (host tools) and the NDK's clang
(target probing):

  native/gen/luajit.h                  version header (from luajit_rolling.h + .relver)
  native/gen/<abi>/lj_vm.S             interpreter (ELF assembly)
  native/gen/<abi>/lj_*def.h           bytecode / fast function / library / recorder / fold tables
  native/gen/<abi>/config.cmake        compile definitions for the target (unwinding mode)

With --host it also builds native/host/win-x64/poblua.dll (LuaJIT + the JNI bridge) for the unit
tests, which run on the desktop JVM.

Requires: Visual Studio Build Tools (C++), the Android NDK (version from app/build.gradle.kts).
Usage   : python tools/build_luajit.py [--abi arm64-v8a ...] [--host]
"""
import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
NATIVE = os.path.join(PROJECT, "native")
LJ = os.path.join(NATIVE, "luajit")
LJ_SRC = os.path.join(LJ, "src")
GEN = os.path.join(NATIVE, "gen")

MIN_API = 26
ABIS = {
    # abi: (clang target triple, MSVC host arch for buildvm: must match the target's pointer size)
    "arm64-v8a": ("aarch64-linux-android", "x64"),
    "x86_64": ("x86_64-linux-android", "x64"),
    "armeabi-v7a": ("armv7a-linux-androideabi", "x86"),
}
# Extra target flags the NDK's CMake toolchain uses by default
ABI_CFLAGS = {
    "armeabi-v7a": ["-march=armv7-a", "-mthumb", "-mfpu=vfpv3-d16", "-mfloat-abi=softfp"],
}
# As in LuaJIT's Makefile (CCOPT, TARGET_XCFLAGS)
TARGET_TCFLAGS = ["-O2", "-fomit-frame-pointer", "-Wall", "-D_FILE_OFFSET_BITS=64", "-D_LARGEFILE_SOURCE", "-U_FORTIFY_SOURCE"]
LJLIB_C = ["lib_base.c", "lib_math.c", "lib_bit.c", "lib_string.c", "lib_table.c", "lib_io.c", "lib_os.c",
           "lib_package.c", "lib_debug.c", "lib_jit.c", "lib_ffi.c", "lib_buffer.c"]


def run(cmd, env=None, cwd=None, capture=False):
    if env is not None and not os.path.dirname(cmd[0]):
        # CreateProcess looks programs up on this process's PATH, not the one in env
        path = next((v for k, v in env.items() if k.upper() == "PATH"), None)
        cmd = [shutil.which(cmd[0], path=path) or cmd[0], *cmd[1:]]
    r = subprocess.run(cmd, env=env, cwd=cwd, capture_output=True, text=True)
    if r.returncode != 0:
        sys.stderr.write(f"FAILED: {' '.join(cmd) if isinstance(cmd, list) else cmd}\n{r.stdout}\n{r.stderr}\n")
        sys.exit(1)
    return r.stdout if capture else None


def msvc_env(arch):
    vswhere = os.path.join(os.environ.get("ProgramFiles(x86)", r"C:\Program Files (x86)"),
                           "Microsoft Visual Studio", "Installer", "vswhere.exe")
    path = subprocess.check_output([vswhere, "-latest", "-products", "*", "-requires",
                                    "Microsoft.VisualStudio.Component.VC.Tools.x86.x64",
                                    "-property", "installationPath"], text=True).strip()
    if not path:
        sys.exit("Visual Studio C++ build tools not found")
    vcvars = os.path.join(path, "VC", "Auxiliary", "Build", "vcvarsall.bat")
    host_arch = {"x64": "x64", "x86": "x64_x86"}[arch]
    out = subprocess.check_output(f'"{vcvars}" {host_arch} >nul 2>nul && set', shell=True, text=True)
    env = {}
    for line in out.splitlines():
        if "=" in line:
            k, v = line.split("=", 1)
            env[k] = v
    return env


def find_ndk():
    props = open(os.path.join(PROJECT, "local.properties")).read()
    sdk = re.search(r"sdk\.dir=(.*)", props).group(1).strip().replace("\\:", ":").replace("\\\\", "\\")
    gradle = open(os.path.join(PROJECT, "app", "build.gradle.kts")).read()
    version = re.search(r'ndkVersion\s*=\s*"([^"]+)"', gradle).group(1)
    ndk = os.path.join(sdk, "ndk", version)
    clang = os.path.join(ndk, "toolchains", "llvm", "prebuilt", "windows-x86_64", "bin", "clang.exe")
    if not os.path.exists(clang):
        sys.exit(f"NDK {version} not found at {ndk}")
    return clang


def cl_compile(env, out_dir, sources, defines, includes, extra=()):
    cmd = ["cl", "/nologo", "/c", "/O2", "/W3", "/D_CRT_SECURE_NO_DEPRECATE",
           "/D_CRT_STDIO_INLINE=__declspec(dllexport)__inline", *extra]
    cmd += [f"/D{d}" for d in defines] + [f"/I{i}" for i in includes] + sources
    run(cmd, env=env, cwd=out_dir)


def build_minilua(work, env):
    cl_compile(env, work, [os.path.join(LJ_SRC, "host", "minilua.c")], [], [])
    run(["link", "/nologo", "/out:minilua.exe", "minilua.obj"], env=env, cwd=work)
    return os.path.join(work, "minilua.exe")


def gen_version(minilua, work):
    shutil.copy(os.path.join(LJ_SRC, "luajit_rolling.h"), work)
    shutil.copy(os.path.join(LJ, ".relver"), os.path.join(work, "luajit_relver.txt"))
    run([minilua, os.path.join(LJ_SRC, "host", "genversion.lua")], cwd=work)
    os.makedirs(GEN, exist_ok=True)
    shutil.copy(os.path.join(work, "luajit.h"), os.path.join(GEN, "luajit.h"))


def probe_target(clang, abi):
    triple, _ = ABIS[abi]
    flags = [f"--target={triple}{MIN_API}", *ABI_CFLAGS.get(abi, []), *TARGET_TCFLAGS]
    macros = run([clang, *flags, "-E", "-dM", os.path.join(LJ_SRC, "lj_arch.h")], capture=True)
    defs = {}
    for line in macros.splitlines():
        m = re.match(r"#define (\S+)(?: (.*))?", line)
        if m:
            defs[m.group(1)] = (m.group(2) or "").strip()
    # Does the toolchain always emit unwind tables? (Makefile: TARGET_TESTUNWIND)
    with tempfile.TemporaryDirectory() as t:
        src = os.path.join(t, "u.c")
        obj = os.path.join(t, "u.o")
        open(src, "w").write("extern void b(void);int a(void){b();return 0;}\n")
        run([clang, *flags, "-c", src, "-o", obj])
        data = open(obj, "rb").read()
        unwind = b"eh_frame" in data or b"__unwind_info" in data
    return flags, defs, unwind


def dasm_flags(defs):
    """DASM_AFLAGS / TARGET_ARCH derivation from LuaJIT's Makefile."""
    def has(name, value="1"):
        return defs.get(name) == value
    if "LJ_TARGET_X64" in defs:
        ljarch = "x64"
    elif "LJ_TARGET_X86" in defs:
        ljarch = "x86"
    elif "LJ_TARGET_ARM" in defs:
        ljarch = "arm"
    elif "LJ_TARGET_ARM64" in defs:
        ljarch = "arm64"
    else:
        sys.exit("unsupported target")
    aflags = ["-D", "ENDIAN_LE" if has("LJ_LE") else "ENDIAN_BE"]
    target_arch = []
    if has("LJ_ARCH_BITS", "64"):
        aflags += ["-D", "P64"]
    if has("LJ_HASJIT"):
        aflags += ["-D", "JIT"]
    if has("LJ_HASFFI"):
        aflags += ["-D", "FFI"]
    if has("LJ_DUALNUM"):
        aflags += ["-D", "DUALNUM"]
    if has("LJ_ARCH_HASFPU"):
        aflags += ["-D", "FPU"]
        target_arch.append("LJ_ARCH_HASFPU=1")
    else:
        target_arch.append("LJ_ARCH_HASFPU=0")
    if not has("LJ_ABI_SOFTFP"):
        aflags += ["-D", "HFABI"]
        target_arch.append("LJ_ABI_SOFTFP=0")
    else:
        target_arch.append("LJ_ABI_SOFTFP=1")
    if has("LJ_NO_UNWIND"):
        aflags += ["-D", "NO_UNWIND"]
        target_arch.append("LUAJIT_NO_UNWIND")
    for flag, name in (("LJ_ABI_PAUTH", "PAUTH"), ("LJ_ABI_BRANCH_TRACK", "BRANCH_TRACK"), ("LJ_ABI_SHADOW_STACK", "SHADOW_STACK")):
        if has(flag):
            aflags += ["-D", name]
    aflags += ["-D", "VER=" + defs.get("LJ_ARCH_VERSION", "")]
    dasm_arch = ljarch
    if ljarch == "x64" and not has("LJ_FR2"):
        dasm_arch = "x86"
    target_arch.append(f"LUAJIT_TARGET=LUAJIT_ARCH_{ljarch}")
    return ljarch, dasm_arch, aflags, target_arch


def gen_abi(abi, clang, minilua, envs, work):
    flags, defs, unwind = probe_target(clang, abi)
    ljarch, dasm_arch, aflags, target_arch = dasm_flags(defs)
    host_arch = ABIS[abi][1]
    env = envs[host_arch]
    w = os.path.join(work, abi)
    os.makedirs(w, exist_ok=True)
    # buildvm_arch.h from the DynASM source of the target
    run([minilua, os.path.join(LJ, "dynasm", "dynasm.lua"), *aflags, "-o", os.path.join(w, "buildvm_arch.h"),
         os.path.join(LJ_SRC, f"vm_{dasm_arch}.dasc")], cwd=LJ_SRC)
    # buildvm for the target, compiled for the host
    src = LJ_SRC
    if ljarch == "arm":
        # lj_ircall.h only knows the GCC names of the 64-bit float conversion helpers that 32-bit
        # ARM needs; buildvm only refers to them by name, so give MSVC the same names.
        src = os.path.join(w, "src")
        shutil.copytree(LJ_SRC, src)
        p = os.path.join(src, "lj_ircall.h")
        text = open(p).read().replace("#if defined(__GNUC__) || defined(__clang__)\n#define fp64_l2d", "#if 1\n#define fp64_l2d")
        open(p, "w").write(text)
    host_srcs = [os.path.join(src, "host", f) for f in
                 ("buildvm.c", "buildvm_asm.c", "buildvm_peobj.c", "buildvm_lib.c", "buildvm_fold.c")]
    cl_compile(env, w, host_srcs, [*target_arch, "LUAJIT_OS=LUAJIT_OS_LINUX"], [w, GEN, src, os.path.join(LJ, "dynasm")])
    run(["link", "/nologo", "/out:buildvm.exe", *[os.path.basename(s).replace(".c", ".obj") for s in host_srcs]], env=env, cwd=w)
    buildvm = os.path.join(w, "buildvm.exe")
    out = os.path.join(GEN, abi)
    os.makedirs(out, exist_ok=True)
    libs = [os.path.join(LJ_SRC, f) for f in LJLIB_C]
    run([buildvm, "-m", "elfasm", "-o", os.path.join(out, "lj_vm.S")], cwd=LJ_SRC)
    for mode, name in (("bcdef", "lj_bcdef.h"), ("ffdef", "lj_ffdef.h"), ("libdef", "lj_libdef.h"), ("recdef", "lj_recdef.h")):
        run([buildvm, "-m", mode, "-o", os.path.join(out, name), *libs], cwd=LJ_SRC)
    run([buildvm, "-m", "folddef", "-o", os.path.join(out, "lj_folddef.h"), os.path.join(LJ_SRC, "lj_opt_fold.c")], cwd=LJ_SRC)
    defines = ["_FILE_OFFSET_BITS=64", "_LARGEFILE_SOURCE"]
    if unwind and not defs.get("LJ_NO_UNWIND") == "1":
        defines.append("LUAJIT_UNWIND_EXTERNAL")
    with open(os.path.join(out, "config.cmake"), "w") as f:
        f.write(f"# Generated by tools/build_luajit.py for {abi} ({ljarch}, DynASM {' '.join(aflags)})\n")
        f.write(f"set(LUAJIT_TARGET_DEFINES {' '.join(defines)})\n")
    print(f"{abi}: {ljarch} dasm={dasm_arch} {' '.join(aflags)} unwind={'external' if 'LUAJIT_UNWIND_EXTERNAL' in defines else 'internal'}")


def build_host_dll(env, minilua, work):
    """Windows x64 build of LuaJIT + the JNI bridge (for the JVM unit tests)."""
    w = os.path.join(work, "host-x64")
    os.makedirs(w, exist_ok=True)
    run([minilua, os.path.join(LJ, "dynasm", "dynasm.lua"), "-LN", "-D", "WIN", "-D", "JIT", "-D", "FFI", "-D", "ENDIAN_LE",
         "-D", "FPU", "-D", "P64", "-o", os.path.join(w, "buildvm_arch.h"), os.path.join(LJ_SRC, "vm_x64.dasc")], cwd=LJ_SRC)
    host_srcs = [os.path.join(LJ_SRC, "host", f) for f in
                 ("buildvm.c", "buildvm_asm.c", "buildvm_peobj.c", "buildvm_lib.c", "buildvm_fold.c")]
    cl_compile(env, w, host_srcs, [], [w, GEN, LJ_SRC, os.path.join(LJ, "dynasm")])
    run(["link", "/nologo", "/out:buildvm.exe", *[os.path.basename(s).replace(".c", ".obj") for s in host_srcs]], env=env, cwd=w)
    buildvm = os.path.join(w, "buildvm.exe")
    libs = [os.path.join(LJ_SRC, f) for f in LJLIB_C]
    run([buildvm, "-m", "peobj", "-o", os.path.join(w, "lj_vm.obj")], cwd=LJ_SRC)
    for mode, name in (("bcdef", "lj_bcdef.h"), ("ffdef", "lj_ffdef.h"), ("libdef", "lj_libdef.h"), ("recdef", "lj_recdef.h")):
        run([buildvm, "-m", mode, "-o", os.path.join(w, name), *libs], cwd=LJ_SRC)
    run([buildvm, "-m", "folddef", "-o", os.path.join(w, "lj_folddef.h"), os.path.join(LJ_SRC, "lj_opt_fold.c")], cwd=LJ_SRC)
    shutil.copy(os.path.join(GEN, "luajit.h"), w)
    java_home = os.environ.get("JAVA_HOME") or os.path.dirname(os.path.dirname(shutil.which("java")))
    jni_inc = [os.path.join(java_home, "include"), os.path.join(java_home, "include", "win32")]
    # Generated headers first, like the Android build
    cl_compile(env, w, [os.path.join(LJ_SRC, "ljamalg.c")], [], [w, LJ_SRC], extra=["/MT"])
    cl_compile(env, w, [os.path.join(NATIVE, "pob_jni.c")], [], [w, LJ_SRC, *jni_inc], extra=["/MT"])
    out_dir = os.path.join(NATIVE, "host", "win-x64")
    os.makedirs(out_dir, exist_ok=True)
    run(["link", "/nologo", "/DLL", f"/OUT:{os.path.join(out_dir, 'poblua.dll')}", "ljamalg.obj", "lj_vm.obj", "pob_jni.obj"], env=env, cwd=w)
    # luajit.exe of the same version, used to precompile bytecode for the APK
    cl_compile(env, w, [os.path.join(LJ_SRC, "luajit.c")], [], [w, LJ_SRC], extra=["/MT"])
    run(["link", "/nologo", f"/OUT:{os.path.join(out_dir, 'luajit.exe')}", "luajit.obj", "ljamalg.obj", "lj_vm.obj"], env=env, cwd=w)
    print(f"host: {out_dir}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--abi", action="append", choices=sorted(ABIS), help="ABIs to generate (default: all)")
    ap.add_argument("--host", action="store_true", help="also build the Windows x64 DLL for unit tests")
    ap.add_argument("--host-only", action="store_true", help="only build the Windows x64 DLL")
    args = ap.parse_args()
    envs = {"x64": msvc_env("x64")}
    abis = [] if args.host_only else (args.abi or list(ABIS))
    if any(ABIS[a][1] == "x86" for a in abis):
        envs["x86"] = msvc_env("x86")
    with tempfile.TemporaryDirectory(prefix="luajit-build-") as work:
        minilua = build_minilua(work, envs["x64"])
        gen_version(minilua, work)
        if abis:
            clang = find_ndk()
            for abi in abis:
                gen_abi(abi, clang, minilua, envs, work)
        if args.host or args.host_only:
            build_host_dll(envs["x64"], minilua, work)


if __name__ == "__main__":
    main()
