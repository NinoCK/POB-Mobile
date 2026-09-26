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
	-- The tabs start their undo history when loading their XML: a new build starts it here, so its
	-- first change can be undone too
	for _, tab in ipairs({ build.skillsTab, build.itemsTab, build.configTab, build.calcsTab }) do
		if not tab.undo[1] then
			tab:ResetUndo()
		end
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

-- PoB's undo (UndoHandler) for the Skills, Items, Configuration and Calcs tabs, as with Ctrl+Z /
-- Ctrl+Y in PoB. Each tab keeps its own history (up to 100 changes, PoB's limit); the tree's
-- history is the app's.
local undoTabs = { skills = "skillsTab", items = "itemsTab", config = "configTab", calcs = "calcsTab" }

-- The tree's undo states are not used, so only the current one is kept
local function trimUndo()
	local spec = build.spec
	if spec and spec.undo then
		for i = #spec.undo, 2, -1 do
			spec.undo[i] = nil
		end
		wipeTable(spec.redo)
	end
end

-- The Skills tab's undo states are shallow copies of the socket groups and gems, so they also keep
-- the skill data of the calculation at that time alive (displaySkillList...: about 0.6 MB per
-- state). CalcSetup rebuilds that data at every calculation, and the app recalculates after an
-- undo, so the states leave it out.
local function patchSkillsUndoState()
	local SkillsTabClass = common.classes["SkillsTab"]
	if not SkillsTabClass or SkillsTabClass.appUndoStatePatched then
		return
	end
	local createUndoState = SkillsTabClass.CreateUndoState
	function SkillsTabClass:CreateUndoState()
		local state = createUndoState(self)
		for _, skillSet in pairs(state.skillSets) do
			for _, list in ipairs({ skillSet.socketGroupList, skillSet.removedSocketGroupList }) do
				for _, group in pairs(list) do
					group.displaySkillList = nil
					group.displaySkillListCalcs = nil
					group.displayGemList = nil
					for _, gem in pairs(group.gemList) do
						gem.displayEffect = nil
					end
				end
			end
		end
		return state
	end
	SkillsTabClass.appUndoStatePatched = true
end

-- { skills = { undo, redo }, items = ..., config = ..., calcs = ... }: what can be undone / redone
function api.undoState()
	local out = { }
	for name, key in pairs(undoTabs) do
		local tab = build[key]
		out[name] = { undo = tab.undo[2] ~= nil, redo = tab.redo[1] ~= nil }
	end
	return out
end

-- Undoes (or with redo = true, redoes) the last change of a tab. args: { tab, redo }
function api.undo(args)
	local tab = build[undoTabs[args.tab] or error("unknown tab " .. tostring(args.tab), 0)]
	if args.redo then
		tab:Redo()
	else
		tab:Undo()
	end
	if args.tab == "items" then
		-- Sockets and slots, as after item changes
		api.refreshItems()
	else
		api.dirty()
		api.recalc()
	end
	return api.state()
end

-- Recalculates the open build
function api.recalc()
	-- (The Skills tab class is loaded with the first build)
	patchSkillsUndoState()
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
