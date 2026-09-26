-- The app's interface to Path of Building, loaded after Host.lua.
--
-- Kotlin calls api.call(name, argsJson); the named function receives the decoded arguments and
-- its result is returned as JSON: { ok = true, result = ... } or { ok = false, error = "..." }.
-- Feature modules (engine/api/*.lua) add their functions to the api table.
local dkjson = require("dkjson")

api = { }


local arrayMeta = { __jsontype = "array" }
local objectMeta = { __jsontype = "object" }

-- Marks a table as a JSON array (so an empty one is encoded as [])
function api.array(t)
	return setmetatable(t or { }, arrayMeta)
end

-- Marks a table as a JSON object (so an empty one is encoded as {})
function api.object(t)
	return setmetatable(t or { }, objectMeta)
end

function api.encode(value)
	return dkjson.encode(value, {
		exception = function(reason, value, state, defaultmessage)
			-- NaN / infinity and other non-JSON values
			if type(value) == "number" then
				if value ~= value then
					return "null"
				end
				return value > 0 and "1e999" or "-1e999"
			end
			return "null"
		end,
	})
end

-- JSON null decodes to nil (an absent value)
function api.decode(text)
	local value, _, err = dkjson.decode(text, 1, nil)
	if err then
		error("invalid JSON arguments: " .. err)
	end
	return value
end

-- Error shown by PoB (launch:ShowErrMsg), cleared once read
function api.takePrompt()
	local msg = launch.promptMsg
	if msg then
		launch.promptMsg = nil
		launch.promptFunc = nil
		main.popups = { }
		return StripEscapes(msg)
	end
end

-- Runs PoB frames until pending work (mode changes, recalculation) is done.
-- Raises PoB's error message if one was shown.
function api.frame(count)
	for _ = 1, count or 1 do
		runCallback("OnFrame")
	end
	local prompt = api.takePrompt()
	if prompt then
		error(prompt, 0)
	end
	build = main.modes["BUILD"]
end

function api.call(name, argsJson)
	local fn = api[name]
	if type(fn) ~= "function" then
		return api.encode({ ok = false, error = "unknown function " .. tostring(name) })
	end
	local ok, result = xpcall(function()
		local args = (argsJson and argsJson ~= "") and api.decode(argsJson) or { }
		return fn(args)
	end, debug.traceback)
	if not ok then
		api.takePrompt()
		return api.encode({ ok = false, error = tostring(result) })
	end
	return api.encode({ ok = true, result = result })
end

-- Loads feature modules
function api.load(name)
	local func, err = __host_loadfile("engine/api/" .. name .. ".lua")
	if not func then
		error(err)
	end
	func()
end

---------------------------------------------------------------------------------------------------
-- Builds
---------------------------------------------------------------------------------------------------

local function resetGlobalCache()
	if GlobalCache and GlobalCache.cachedData then
		wipeGlobalCache()
	end
end

-- Opens a build from PoB XML ({ xml, name }), or a new empty build when xml is absent.
-- Old builds are converted to the current game version like PoB's conversion prompt does.
-- Does what main:SetMode("BUILD", ...) and the next frame do, without drawing: buildMode:Init
-- loads the build and calculates it.
function api.loadBuild(args)
	resetGlobalCache()
	-- Gem counts of the previous build (refreshed below)
	if GlobalGemAssignments then
		wipeTable(GlobalGemAssignments)
	end
	if main.mode then
		main:CallMode("Shutdown")
	end
	main.mode = "BUILD"
	main.newMode = nil
	build:Init(false, args.name or "", args.xml, true)
	local prompt = api.takePrompt()
	if main.newMode or not build.targetVersion or prompt then
		-- Init failed and asked for the build list
		main.newMode = nil
		error(prompt or "the build could not be opened", 0)
	end
	-- Jewel socket slots follow the allocated tree (the Items tab updates them when drawn)
	build.itemsTab:UpdateSockets()
	build.skillsTab:UpdateGlobalGemCountAssignments()
	-- What the frame after a calculation does
	build.configTab.calcFunc, build.configTab.calcBase = build.calcsTab:GetMiscCalculator()
	build:RefreshSkillSelectControls(build.controls, build.mainSocketGroup, "")
	return true
end

-- The open build as PoB XML (what PoB saves to a .xml file / encodes in build codes)
function api.saveXml()
	local xml = build:SaveDB("code")
	if not xml then
		error("the build could not be saved", 0)
	end
	return xml
end

-- One recalculation: the buildFlag block of buildMode:OnFrame (Build.lua), without drawing the
-- current tab. BuildOutput computes the MAIN (sidebar) and CALCS (Calcs tab) environments and
-- the misc calculator used for comparisons; RefreshStatList rebuilds the sidebar and warnings.
local function recalcOnce()
	-- Normally refreshed when the Skills tab is drawn; the gem count warnings read it
	build.skillsTab:UpdateGlobalGemCountAssignments()
	wipeGlobalCache()
	build.outputRevision = build.outputRevision + 1
	build.buildFlag = false
	build.calcsTab:BuildOutput()
	build:RefreshStatList()
	build.configTab.calcFunc, build.configTab.calcBase = build.calcsTab:GetMiscCalculator()
	build:RefreshSkillSelectControls(build.controls, build.mainSocketGroup, "")
end

-- PoB keeps up to 101 undo states per tab (copies of items, skills, configuration...). The app does
-- not use PoB's undo, so only the current state is kept, bounding memory use.
local function trimUndo()
	for _, handler in ipairs({ build.spec, build.skillsTab, build.itemsTab, build.configTab, build.calcsTab }) do
		if handler and handler.undo then
			for i = #handler.undo, 2, -1 do
				handler.undo[i] = nil
			end
			wipeTable(handler.redo)
		end
	end
end

-- Recalculates the open build
function api.recalc()
	trimUndo()
	local level = build.characterLevel
	recalcOnce()
	-- In automatic level mode PoB sets the level after the calculation; calculate again with it
	if build.characterLevelAutoMode and build.characterLevel ~= level then
		recalcOnce()
	end
	return true
end

-- Recalculates if something marked the build as changed
function api.recalcIfNeeded()
	if build.buildFlag then
		api.recalc()
	end
end

-- Marks the build as changed (unsaved, needs a recalculation)
function api.dirty()
	build.modFlag = true
	build.buildFlag = true
end

function api.memory()
	return collectgarbage("count")
end
