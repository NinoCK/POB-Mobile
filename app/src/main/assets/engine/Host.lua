-- Runs Path of Building (PoE2) headless inside the app, like PoB's HeadlessWrapper.lua.
--
-- PoB's files come from the app through the host (native/pob_jni.c): paths are relative to PoB's
-- "src" folder, like the working directory of the desktop program. PoB's own Lua modules from
-- "runtime/lua" live under "lua/". Absolute paths still use the real file system (the user folder).
--
-- Called with a config table: { userPath = "<writable folder>/" }
local config = ...

local host_loadfile = __host_loadfile
local host_readfile = __host_readfile
local host_log = __host_log

print = function(...)
	local parts = { }
	for i = 1, select("#", ...) do
		parts[i] = tostring(select(i, ...))
	end
	host_log(table.concat(parts, "\t"))
end

local function isAbsolute(path)
	return path:match("^/") or path:match("^%a:[/\\]")
end

-- "./Modules\\Foo.lua" -> "Modules/Foo.lua"
local function normalize(path)
	path = path:gsub("\\", "/"):gsub("^%./", "")
	return path
end

local l_loadfile = loadfile
function loadfile(name, ...)
	if name == nil or isAbsolute(name) then
		return l_loadfile(name, ...)
	end
	return host_loadfile(normalize(name))
end

function dofile(name)
	local func, err = loadfile(name)
	if not func then
		error(err, 2)
	end
	return func()
end

-- Read-only file object over a string, for PoB files read with io.open
local memFileClass = { }
memFileClass.__index = memFileClass
local function newMemFile(data)
	return setmetatable({ data = data, pos = 1 }, memFileClass)
end
function memFileClass:read(fmt, ...)
	fmt = fmt or "*l"
	local results = { }
	local formats = { fmt, ... }
	for i, f in ipairs(formats) do
		local r
		if type(f) == "number" then
			if self.pos <= #self.data then
				r = self.data:sub(self.pos, self.pos + f - 1)
				self.pos = self.pos + f
			end
		elseif f:match("^%*?a") then
			r = self.data:sub(self.pos)
			self.pos = #self.data + 1
		elseif f:match("^%*?[lL]") then
			if self.pos <= #self.data then
				local s, e = self.data:find("\r?\n", self.pos)
				if s then
					r = self.data:sub(self.pos, f:match("L") and e or s - 1)
					self.pos = e + 1
				else
					r = self.data:sub(self.pos)
					self.pos = #self.data + 1
				end
			end
		elseif f:match("^%*?n") then
			local s, e = self.data:find("^%s*[-+]?%d*%.?%d+[eE]?[-+]?%d*", self.pos)
			if s then
				r = tonumber(self.data:sub(s, e))
				self.pos = e + 1
			end
		end
		results[i] = r
		if r == nil then
			break
		end
	end
	return unpack(results, 1, #formats)
end
function memFileClass:lines()
	return function()
		return self:read("*l")
	end
end
function memFileClass:seek(whence, offset)
	whence = whence or "cur"
	offset = offset or 0
	if whence == "set" then
		self.pos = offset + 1
	elseif whence == "end" then
		self.pos = #self.data + offset + 1
	else
		self.pos = self.pos + offset
	end
	return self.pos - 1
end
function memFileClass:close()
	return true
end
function memFileClass:setvbuf() return true end
function memFileClass:flush() return true end
function memFileClass:write()
	return nil, "read-only file"
end

local io_open = io.open
function io.open(name, mode)
	mode = mode or "r"
	if isAbsolute(name) or mode:match("[wa+]") then
		return io_open(name, mode)
	end
	name = normalize(name)
	-- The passive tree images are not shipped (PoB only checks that they exist)
	if name:match("^TreeData/.*%.png$") or name:match("^TreeData/.*%.dds%.zst$") or name:match("^TreeData/.*%.jpg$") then
		return nil, name .. ": No such file or directory"
	end
	local data = host_readfile(name)
	if not data then
		return nil, name .. ": No such file or directory"
	end
	return newMemFile(data)
end

local io_lines = io.lines
function io.lines(name, ...)
	if name == nil or isAbsolute(name) then
		return io_lines(name, ...)
	end
	local file, err = io.open(name, "r")
	if not file then
		error(err, 2)
	end
	return file:lines()
end

-- require: PoB modules ("Modules.CalcBase") and PoB's pure Lua libraries (dkjson, xml, base64, sha1)
package.path = "?.lua;lua/?.lua;lua/?/init.lua"
package.cpath = ""
local function hostSearcher(name)
	local tried = { }
	local file = name:gsub("%.", "/")
	for pattern in package.path:gmatch("[^;]+") do
		local path = pattern:gsub("%?", file)
		local func, err = host_loadfile(path)
		if func then
			return func
		elseif err and not err:match("^cannot open") then
			error(err, 2)
		end
		tried[#tried + 1] = "\n\tno file '" .. path .. "'"
	end
	return table.concat(tried)
end
package.loaders = { package.loaders[1], hostSearcher }

-- lua-utf8 is a C module in PoB's runtime. PoB only applies it to ASCII text outside of text
-- editing (thousands separators), where byte-based functions give the same results.
package.preload["lua-utf8"] = function()
	local utf8 = { }
	local charPattern = "[%z\1-\127\194-\244][\128-\191]*"
	for _, name in ipairs({ "byte", "find", "format", "gmatch", "gsub", "lower", "match", "rep", "sub", "upper" }) do
		utf8[name] = string[name]
	end
	utf8.charpattern = charPattern
	function utf8.len(s)
		local n = 0
		for _ in s:gmatch(charPattern) do
			n = n + 1
		end
		return n
	end
	function utf8.reverse(s)
		local chars = { }
		for c in s:gmatch(charPattern) do
			table.insert(chars, 1, c)
		end
		return table.concat(chars)
	end
	function utf8.char(...)
		local out = { }
		for i = 1, select("#", ...) do
			local c = select(i, ...)
			if c < 0x80 then
				out[i] = string.char(c)
			elseif c < 0x800 then
				out[i] = string.char(0xC0 + math.floor(c / 0x40), 0x80 + c % 0x40)
			elseif c < 0x10000 then
				out[i] = string.char(0xE0 + math.floor(c / 0x1000), 0x80 + math.floor(c / 0x40) % 0x40, 0x80 + c % 0x40)
			else
				out[i] = string.char(0xF0 + math.floor(c / 0x40000), 0x80 + math.floor(c / 0x1000) % 0x40, 0x80 + math.floor(c / 0x40) % 0x40, 0x80 + c % 0x40)
			end
		end
		return table.concat(out)
	end
	-- Byte position of the next (dir 1) or previous (dir -1) character from byte position i
	function utf8.next(s, i, dir)
		dir = dir or 1
		i = i or 0
		if dir < 0 then
			i = i - 1
			while i > 0 and s:byte(i) >= 0x80 and s:byte(i) < 0xC0 do
				i = i - 1
			end
			return i > 0 and i or nil
		end
		i = i + 1
		while i <= #s and s:byte(i) >= 0x80 and s:byte(i) < 0xC0 do
			i = i + 1
		end
		return i <= #s and i or nil
	end
	return utf8
end

-- PoB's headless stubs of the SimpleGraphic API, then the functions that need real behaviour
dofile("_SimpleGraphic.def.lua")

GetTime = __host_time
function ConPrintf(fmt, ...)
	host_log(string.format(fmt, ...))
end
function ConPrintTable() end
function ConExecute() end
function ConClear() end
function Inflate(data)
	return __host_inflate(data) or ""
end
function Deflate(data)
	return __host_deflate(data) or ""
end
function GetScriptPath()
	return ""
end
-- Differs from the script path, so PoB keeps its settings in the user folder like an installed copy
function GetRuntimePath()
	return "runtime"
end
function GetUserPath()
	return (config.userPath:gsub("/$", ""))
end
function GetWorkDir()
	return ""
end
function Copy() end
function Paste() end
function OpenURL() end
function SpawnProcess() end
function Restart() end
function Exit() end

-- Text measuring: the app lays text out itself, so everything "fits" (PoB shortens labels that
-- do not fit its layout with DrawStringCursorIndex)
function DrawStringCursorIndex(height, font, text)
	return #text + 1
end

function GetVirtualScreenSize()
	return 1920, 1080
end

-- Callbacks (HeadlessWrapper)
__callbackTable__ = { }
function runCallback(name, ...)
	if __callbackTable__[name] then
		return __callbackTable__[name](...)
	elseif __mainObject__ and __mainObject__[name] then
		return __mainObject__[name](__mainObject__, ...)
	end
end

-- No network access: PoB checks for lcurl before using it
local l_require = require
function require(name)
	if name == "lcurl.safe" then
		return
	end
	return l_require(name)
end

arg = { }

-- LuaJIT's interpreter only: PoB's code is branchy and short-running, so trace compilation costs
-- more than it saves (opening a build took 6x longer with the JIT on in the emulator), and no
-- executable memory is needed on devices that restrict it
jit.off()

dofile("Launch.lua")

-- No updates or network access (PoB's update check runs a sub-script)
function launch:CheckForUpdate() end

-- Launch.lua lets the heap grow to 4x its live size between collections, a desktop setting; on
-- phones collect at 2x (Lua's default) to keep memory use lower
collectgarbage("setpause", 200)

-- Use the bundled mod cache
__mainObject__.continuousIntegrationMode = false

runCallback("OnInit")
runCallback("OnFrame") -- Need at least one frame for everything to initialise

if __mainObject__.promptMsg then
	error("Path of Building failed to start: " .. tostring(__mainObject__.promptMsg))
end

-- A fix on top of PoB: copyTableSafe set a copy's metatable before filling it in. PoB's objects
-- hold parent class proxies that are their own metatable, with __newindex = the object, so the
-- fields of a proxy's copy were written into the original object instead. The deep copy in
-- PassiveSpec:SwitchAttributeNode (a tree node with its ModList objects) thereby linked the shared
-- tree's attribute options to each new copy, and the next copy copied all of them again: memory
-- grew with every attribute node switch, exponentially in some hash orders. Here the metatable is
-- set after the fields, and the copy of a table that is its own metatable is its own metatable.
-- Other copies are unchanged (no other metatables with __newindex are copied).
do
	local subTableMap = { }
	function copyTableSafe(tbl, noRecurse, preserveMeta, isSubTable)
		local out = { }
		if not noRecurse then
			subTableMap[tbl] = out
		end
		for k, v in pairs(tbl) do
			if not noRecurse and type(v) == "table" then
				out[k] = subTableMap[v] or copyTableSafe(v, false, preserveMeta, true)
			else
				out[k] = v
			end
		end
		if preserveMeta then
			local meta = getmetatable(tbl)
			setmetatable(out, meta == tbl and out or meta)
		end
		if not noRecurse and not isSubTable then
			wipeTable(subTableMap)
		end
		return out
	end
end

build = __mainObject__.main.modes["BUILD"]
