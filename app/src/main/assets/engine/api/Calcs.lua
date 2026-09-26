-- The Calcs tab: sections of CalcSections.lua evaluated like CalcSectionControl, and breakdowns
-- built by PoB's own CalcBreakdownControl (run without drawing, then serialised).
-- The Calcs tab has its own environment (calcsTab.calcsEnv, "CALCS" mode) with its own skill
-- selection and calculation mode.

local function calcsActor(actorName)
	local calcsTab = build.calcsTab
	local env = calcsTab.calcsEnv
	if not env then
		return nil
	end
	if actorName == "minion" or (actorName == nil and calcsTab.input.showMinion) then
		return env.minion or env.player
	end
	return env.player
end

-- formatCalcStr raises on boolean outputs (gsub replacement), as the Compare tab knows
local function formatSafe(format, actor, colData)
	local ok, text = pcall(formatCalcStr, format, actor, colData)
	return ok and text or ""
end

local function hasBreakdownEntries(colData)
	for _, entry in ipairs(colData) do
		if entry.breakdown or entry.modName then
			return true
		end
	end
	return false
end

-- Visible sections for the current Calcs selection. args: { actor = "player" | "minion" }
function api.calcSections(args)
	local calcsTab = build.calcsTab
	local actor = calcsActor(args.actor)
	local result = api.array()
	if not actor then
		return result
	end
	for _, section in ipairs(calcsTab.sectionList) do
		if section.id ~= "SkillSelect" and calcsTab:CheckFlag(section, actor) then
			local sec = { id = section.id, group = section.group, colour = section.colour, subsections = api.array() }
			local anyRow = false
			for subIdx, subSec in ipairs(section.subSection) do
				local sub = {
					label = subSec.label,
					collapsed = subSec.defaultCollapsed and true or false,
					extra = subSec.data.extra and formatSafe(subSec.data.extra, actor) or nil,
					rows = api.array(),
				}
				for rowIdx, rowData in ipairs(subSec.data) do
					if calcsTab:CheckFlag(rowData, actor) then
						local row = { label = rowData.label, labelColor = rowData.color, cells = api.array() }
						for colIdx, colData in ipairs(rowData) do
							local cell = { text = "" }
							if colData.format and calcsTab:CheckFlag(colData, actor) then
								cell.text = formatSafe(colData.format, actor, colData)
								if hasBreakdownEntries(colData) then
									cell.key = section.id .. "/" .. subIdx .. "/" .. rowIdx .. "/" .. colIdx
								end
							end
							row.cells[#row.cells + 1] = cell
						end
						sub.rows[#sub.rows + 1] = row
						anyRow = true
					end
				end
				sec.subsections[#sec.subsections + 1] = sub
			end
			if anyRow then
				result[#result + 1] = sec
			end
		end
	end
	return result
end

---------------------------------------------------------------------------------------------------
-- Breakdowns
---------------------------------------------------------------------------------------------------

local breakdownControl
local function getBreakdownControl(envName)
	if not breakdownControl or breakdownControl.calcsTab ~= build.calcsTab then
		breakdownControl = new("CalcBreakdownControl"):CalcBreakdownControl(build.calcsTab)
		-- An empty breakdown would otherwise clear the Calcs tab's hover state
		breakdownControl.clearDisplayFunc = function() end
	end
	breakdownControl.envName = envName
	return breakdownControl
end

-- Table cells as CalcBreakdownControl:DrawBreakdownTable draws them
local function tableCell(value)
	local s = tostring(value)
	local _, alpha = s:gsub("%a", " ")
	local _, notes = s:gsub(" to ", " ")
	local _, paren = s:gsub("%b()", " ")
	if alpha == 0 or notes > 0 or paren > 0 then
		return (formatNumSep(s)), true
	end
	return s, false
end

local function tooltipLines(fn)
	local tt = new("Tooltip"):Tooltip()
	if not pcall(fn, tt) then
		return nil
	end
	local lines = api.array()
	for _, line in ipairs(tt.lines) do
		if line.text then
			lines[#lines + 1] = line.text
		end
	end
	return lines
end

local function serialise(sectionList)
	local out = api.array()
	for _, sec in ipairs(sectionList) do
		if sec.type == "TEXT" then
			local lines = api.array()
			for i, line in ipairs(sec.lines) do
				lines[i] = (formatNumSep(line))
			end
			out[#out + 1] = { type = "text", lines = lines }
		elseif sec.type == "TABLE" then
			-- A column is drawn only when some row has a value for it
			local cols = { }
			for _, col in ipairs(sec.colList) do
				for _, row in ipairs(sec.rowList) do
					if row[col.key] then
						cols[#cols + 1] = col
						break
					end
				end
			end
			local columns = api.array()
			for i, col in ipairs(cols) do
				columns[i] = { label = col.label, right = col.right and true or false }
			end
			local rows = api.array()
			for _, row in ipairs(sec.rowList) do
				local cells = api.array()
				for i, col in ipairs(cols) do
					local v = row[col.key]
					cells[i] = v and (tableCell(v)) or ""
				end
				local r = { cells = cells }
				if row.sourceNameNode then
					r.nodeId = row.sourceNameNode.id
				end
				if row.sourceTooltip then
					r.sourceTotals = tooltipLines(row.sourceTooltip)
				end
				rows[#rows + 1] = r
			end
			out[#out + 1] = { type = "table", label = sec.label, footer = sec.footer, columns = columns, rows = rows }
		end
		-- RADIUS sections (area visualiser) are not shown by the app
	end
	return out
end

local function breakdownFromDisplayData(displayData, actorName, envName)
	local ctl = getBreakdownControl(envName)
	ctl:SetBreakdownData()
	local ok, err = pcall(ctl.SetBreakdownData, ctl, displayData, true, actorName)
	local result
	if not ok then
		ctl:SetBreakdownData()
		error(err, 0)
	end
	result = { sections = ctl.shown and serialise(ctl.sectionList) or api.array() }
	ctl:SetBreakdownData()
	return result
end

-- Breakdown of a Calcs tab cell. args: { key = "<section>/<sub>/<row>/<col>", actor }
function api.calcBreakdown(args)
	local secId, subIdx, rowIdx, colIdx = tostring(args.key):match("^([^/]+)/(%d+)/(%d+)/(%d+)$")
	local colData, rowData, subSec
	for _, section in ipairs(build.calcsTab.sectionList) do
		if section.id == secId then
			subSec = section.subSection[tonumber(subIdx)]
			rowData = subSec and subSec.data[tonumber(rowIdx)]
			colData = rowData and rowData[tonumber(colIdx)]
		end
	end
	if not colData then
		error("unknown breakdown " .. tostring(args.key), 0)
	end
	local actorName = args.actor
	if actorName == nil then
		actorName = build.calcsTab.input.showMinion and build.calcsTab.calcsEnv.minion and "minion" or "player"
	end
	local result = breakdownFromDisplayData(colData, actorName)
	result.title = rowData.label or subSec.label
	local actor = calcsActor(actorName)
	result.value = colData.format and actor and formatSafe(colData.format, actor, colData) or nil
	return result
end

-- Breakdown of a sidebar stat (what PoB shows when hovering it). args: { stat, childStat?, actor }
function api.sidebarBreakdown(args)
	local actorName = args.actor or "player"
	local list = actorName == "minion" and build.minionDisplayStats or build.displayStats
	for _, sd in ipairs(list) do
		if sd.stat == args.stat and sd.childStat == args.childStat then
			local key = build:GetStatBreakdownKey(sd, actorName)
			if not key then
				return { sections = api.array(), title = sd.label }
			end
			local displayData = build:GetSidebarBreakdown(key, sd.modNames, sd.ignoredSections, actorName)
			local result = breakdownFromDisplayData(displayData, actorName, "mainEnv")
			result.title = sd.label
			return result
		end
	end
	error("unknown stat " .. tostring(args.stat), 0)
end

---------------------------------------------------------------------------------------------------
-- Calcs tab skill selection and calculation mode (the SkillSelect section)
---------------------------------------------------------------------------------------------------

local buffModes = {
	{ label = "Unbuffed", mode = "UNBUFFED" },
	{ label = "Buffed", mode = "BUFFED" },
	{ label = "In Combat", mode = "COMBAT" },
	{ label = "Effective DPS", mode = "EFFECTIVE" },
}

local function calcsGroup()
	return build.skillsTab.socketGroupList[build.calcsTab.input.skill_number]
end

local function calcsActive()
	local group = calcsGroup()
	if not group or not group.displaySkillListCalcs then
		return nil
	end
	return group.displaySkillListCalcs[group.mainActiveSkillCalcs or 1]
end

function api.calcsSelection()
	local calcsTab = build.calcsTab
	local skillsTab = build.skillsTab
	local env = calcsTab.calcsEnv
	local res = {
		socketGroup = calcsTab.input.skill_number,
		buffMode = calcsTab.input.misc_buffMode,
		buffModes = api.array(),
		showMinion = calcsTab.input.showMinion and true or false,
		groups = api.array(),
		hasMinion = env and env.minion ~= nil or false,
	}
	for i, m in ipairs(buffModes) do
		res.buffModes[i] = { label = m.label, mode = m.mode }
	end
	for i, group in ipairs(skillsTab.socketGroupList) do
		res.groups[i] = { label = group.displayLabel or group.label or "", weaponSet = skillsTab:GetSocketGroupWeaponSetLabel(group) }
	end
	local group, active = calcsGroup(), calcsActive()
	if group and group.displaySkillListCalcs then
		res.mainActiveSkill = group.mainActiveSkillCalcs or 1
		res.activeSkills = api.array()
		for i, activeSkill in ipairs(group.displaySkillListCalcs) do
			res.activeSkills[i] = calcsTab.calcs.getActiveSkillDisplayName(activeSkill)
		end
	end
	if active then
		local effect = active.activeEffect
		local granted, src = effect.grantedEffect, effect.srcInstance
		local flags = ((effect.statSetCalcs or effect.statSet) or { }).skillFlags or { }
		if granted.statSets and #granted.statSets > 0 then
			res.statSets = api.array()
			for i, statSet in ipairs(granted.statSets) do
				res.statSets[i] = statSet.label
			end
			res.statSet = src.statSetCalcs and src.statSetCalcs[granted.id] or 1
		end
		if granted.parts and #granted.parts > 1 then
			res.parts = api.array()
			for i, part in ipairs(granted.parts) do
				res.parts[i] = part.name
			end
			res.skillPart = src.skillPartCalcs or 1
		end
		if flags.multiStage then
			res.stageCount = src.skillStageCountCalcs or (active.skillData and active.skillData.stagesMin) or 1
		end
		if flags.mine then
			res.hasMineCount = true
			res.mineCount = src.skillMineCountCalcs
		end
		if active.minion and active.minion.activeSkillList and active.minion.activeSkillList[1] then
			res.minionSkills = api.array()
			for i, minionSkill in ipairs(active.minion.activeSkillList) do
				res.minionSkills[i] = minionSkill.activeEffect.grantedEffect.name
			end
			res.minionSkill = src.skillMinionSkillCalcs or 1
		end
	end
	if env then
		local output = env.player.output
		res.buffList = output.BuffList
		res.combatList = output.CombatList
		res.curseList = output.CurseList
	end
	return res
end

-- Changes the Calcs tab selection. args: { socketGroup | mainActiveSkill | statSet | skillPart |
-- stageCount | mineCount | minionSkill | buffMode | showMinion }
function api.calcsSelect(args)
	local input = build.calcsTab.input
	local active = calcsActive()
	local src = active and active.activeEffect.srcInstance
	local granted = active and active.activeEffect.grantedEffect
	if args.socketGroup then
		input.skill_number = args.socketGroup
	elseif args.mainActiveSkill then
		calcsGroup().mainActiveSkillCalcs = args.mainActiveSkill
	elseif args.statSet and src then
		src.statSetCalcs = src.statSetCalcs or { }
		src.statSetCalcs[granted.id] = args.statSet
	elseif args.skillPart and src then
		src.skillPartCalcs = args.skillPart
	elseif args.stageCount ~= nil and src then
		src.skillStageCountCalcs = tonumber(args.stageCount)
	elseif args.mineCount ~= nil and src then
		local count = tonumber(args.mineCount)
		src.skillMineCountCalcs = count and count >= 0 and count or nil
	elseif args.minionSkill and src then
		src.skillMinionSkillCalcs = args.minionSkill
	elseif args.buffMode then
		input.misc_buffMode = args.buffMode
	elseif args.showMinion ~= nil then
		-- Display only: no recalculation needed
		input.showMinion = args.showMinion and true or false
		build.calcsTab:AddUndoState()
		return api.calcsSelection()
	end
	-- As PoB's controls (all but the active skill's)
	if not args.mainActiveSkill then
		build.calcsTab:AddUndoState()
	end
	api.dirty()
	api.recalc()
	return api.calcsSelection()
end
