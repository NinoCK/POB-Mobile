"""
Runs the desktop Path of Building (PoE2) headless with its own bundled LuaJIT (runtime/lua51.dll),
to make test fixtures and record the results the original program computes.

  python tools/pob_reference.py generate    builds the characters in tools/fixtures/recipes.lua and
                                            saves them as PoB XML in app/src/test/resources/builds
  python tools/pob_reference.py reference   loads every fixture and writes <name>.expected.json:
                                            PoB's main output table and the app API's sidebar state
                                            (assets/engine, run on the original program)
  python tools/pob_reference.py modcache    writes assets/pob/Data/ModCache.lua: PoB's bundled mod parser
                                            cache plus the lines PoB parses at startup that it lacks
                                            (run after tools/build_pob_assets.py)

The unit tests compare the app's engine (LuaJIT built from source + assets/pob) with these files.
Windows only (PoB's runtime is a Windows build). Options: --pob ../PathOfBuilding-PoE2
"""
import argparse
import ctypes
import glob
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
FIXTURES = os.path.join(PROJECT, "app", "src", "test", "resources", "builds")
ENGINE = os.path.join(PROJECT, "app", "src", "main", "assets", "engine")
RECIPES = os.path.join(HERE, "fixtures", "recipes.lua")


class PoB:
    """The desktop PoB program loaded headless in PoB's LuaJIT."""

    def __init__(self, pob_root):
        runtime = os.path.join(pob_root, "runtime")
        os.add_dll_directory(runtime)
        lua = ctypes.CDLL(os.path.join(runtime, "lua51.dll"))
        lua.luaL_newstate.restype = ctypes.c_void_p
        lua.luaL_openlibs.argtypes = [ctypes.c_void_p]
        lua.luaL_loadbuffer.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_size_t, ctypes.c_char_p]
        lua.lua_pcall.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_int, ctypes.c_int]
        lua.lua_tolstring.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.POINTER(ctypes.c_size_t)]
        lua.lua_tolstring.restype = ctypes.c_void_p
        lua.lua_settop.argtypes = [ctypes.c_void_p, ctypes.c_int]
        self.lua = lua
        self.L = lua.luaL_newstate()
        lua.luaL_openlibs(self.L)
        os.chdir(os.path.join(pob_root, "src"))
        rt = runtime.replace("\\", "/")
        engine = ENGINE.replace("\\", "/")
        self.run(f"""
            package.path = "{rt}/lua/?.lua;{rt}/lua/?/init.lua;" .. package.path
            package.cpath = "{rt}/?.dll;" .. package.cpath
            arg = {{}}
            -- The app's engine scripts load their modules through this host function
            function __host_loadfile(path)
                return loadfile("{engine}/" .. path:gsub("^engine/", ""))
            end
            local l_print = print
            print = function(...) end
            dofile("HeadlessWrapper.lua")
            print = l_print
            assert(loadfile("{engine}/Api.lua"))()
        """)

    def run(self, code):
        """Runs Lua code; returns its first result as a string (or None)."""
        data = code.encode("utf-8")
        L, lua = self.L, self.lua
        if lua.luaL_loadbuffer(L, data, len(data), b"=pob_reference") != 0 or lua.lua_pcall(L, 0, 1, 0) != 0:
            err = self._string(-1)
            lua.lua_settop(L, 0)
            raise RuntimeError(err)
        result = self._string(-1)
        lua.lua_settop(L, 0)
        return result

    def _string(self, idx):
        size = ctypes.c_size_t()
        ptr = self.lua.lua_tolstring(self.L, idx, ctypes.byref(size))
        return ctypes.string_at(ptr, size.value).decode("utf-8", errors="replace") if ptr else None


def lua_string(s):
    return "\"" + "".join(c if 32 <= ord(c) < 127 and c not in "\"\\" else "\\%03d" % b
                          for c in s for b in c.encode("utf-8")) + "\""


def generate(pob):
    names = pob.run(f"""
        recipes = dofile({lua_string(RECIPES)})
        local names = {{ }}
        for name in pairs(recipes) do names[#names + 1] = name end
        table.sort(names)
        return table.concat(names, ",")
    """).split(",")
    os.makedirs(FIXTURES, exist_ok=True)
    for name in names:
        xml = pob.run(f"""
            newBuild()
            recipes[{lua_string(name)}]()
            build.buildFlag = true
            runCallback("OnFrame")
            if launch.promptMsg then error(launch.promptMsg) end
            return build:SaveDB("code")
        """)
        with open(os.path.join(FIXTURES, name + ".xml"), "w", encoding="utf-8", newline="\n") as f:
            f.write(xml)
        print(f"generated {name}")


def reference(pob):
    for path in sorted(glob.glob(os.path.join(FIXTURES, "*.xml"))):
        name = os.path.splitext(os.path.basename(path))[0]
        xml = open(path, encoding="utf-8").read()
        text = pob.run(f"""
            local ok, err = pcall(function()
                api.loadBuild({{ xml = {lua_string(xml)}, name = {lua_string(name)} }})
            end)
            if not ok then error(err) end
            for _, module in ipairs({{ "Stats", "Tree", "Calcs", "Config", "Skills", "Items", "Power", "Craft" }}) do api.load(module) end
            api.recalc()
            local out = build.calcsTab.mainOutput
            local flat = {{ }}
            local function put(prefix, t)
                for k, v in pairs(t) do
                    local key = prefix .. tostring(k)
                    local tv = type(v)
                    if tv == "number" then
                        flat[key] = string.format("%.17g", v)
                    elseif tv == "boolean" or tv == "string" then
                        flat[key] = tostring(v)
                    end
                end
            end
            put("", out)
            if out.Minion then
                put("Minion.", out.Minion)
            end
            return api.encode({{
                mainOutput = flat,
                state = api.state(),
                className = build.spec.curClassName,
                ascendClassName = build.spec.curAscendClassName,
                level = build.characterLevel,
            }})
        """)
        data = json.loads(text)
        with open(os.path.join(FIXTURES, name + ".expected.json"), "w", encoding="utf-8", newline="\n") as f:
            json.dump(data, f, indent=1, sort_keys=True)
        out = data["mainOutput"]
        print(f"{name}: {data['ascendClassName']} lvl {data['level']} "
              f"CombinedDPS={out.get('CombinedDPS')} Life={out.get('Life')} ES={out.get('EnergyShield')}")


def modcache(pob):
    """PoB's mod cache with the entries it is missing for startup (item bases, runes, uniques).

    Existing entries are kept exactly, so results stay identical to the desktop program; the new
    entries are what PoB computes on the fly when a line is not cached."""
    target = os.path.join(PROJECT, "app", "src", "main", "assets", "pob", "Data", "ModCache.lua").replace("\\", "/")
    count = pob.run(f"""
        -- Create the tabs of a build, so every class that parses mods at load time has run
        newBuild()
        local n = 0
        for _ in pairs(modLib.parseModCache) do n = n + 1 end
        local l_open = io.open
        io.open = function(name, mode)
            if name == "Data/ModCache.lua" then
                return l_open({lua_string(target)}, mode)
            end
            return l_open(name, mode)
        end
        main:SaveModCache()
        io.open = l_open
        return tostring(n)
    """)
    print(f"mod cache: {count} entries -> {target}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("command", choices=["generate", "reference", "modcache"])
    ap.add_argument("--pob", default=os.path.join(PROJECT, "..", "PathOfBuilding-PoE2"))
    args = ap.parse_args()
    pob = PoB(os.path.abspath(args.pob))
    if args.command == "generate":
        generate(pob)
    elif args.command == "modcache":
        modcache(pob)
    else:
        reference(pob)


if __name__ == "__main__":
    sys.exit(main())
