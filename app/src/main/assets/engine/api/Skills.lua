-- The Skills tab (SkillsTab.lua): socket groups and gems, edited the way PoB's controls do.
-- Group indices change when PoB adds or removes item / tree granted groups during a calculation,
-- so the app always works from the latest listing.

local function effectOf(gem)
	return gem.grantedEffect or (gem.gemData and gem.gemData.grantedEffect)
end

-- First gem of an item, tree or default attack group: read-only in PoB
local function isLockedGem(group, index)
	return index == 1 and (group.source or group.sourceItem or group.sourceNode) and true or false
end

-- Mirrors SkillsTab:AddSocketGroupTooltip's classification of gems
local function gemStatus(group, gem, active)
	if active[gem] then
		return "active", nil
	end
	local effect = effectOf(gem)
	local display = gem.displayEffect or gem
	if not effect then
		return "inactive", "Unsupported"
	end
	if not gem.enabled then
		return "inactive", "Disabled"
	end
	if not group.enabled then
		return "inactive", nil
	end
	if effect.support then
		if display.superseded then
			return "inactive", "Superseded"
		end
		if (not display.isSupporting or not next(display.isSupporting)) and #(group.displaySkillList or { }) > 0 then
			return "inactive", "Cannot apply to any of the active skills"
		end
	end
	return "inactive", nil
end

local colourCodes = { colorCodes.STRENGTH, colorCodes.DEXTERITY, colorCodes.INTELLIGENCE }

function api.skills()
	local skillsTab = build.skillsTab
	local calcs = build.calcsTab.calcs
	local result = {
		mainSocketGroup = build.mainSocketGroup,
		activeSkillSetId = skillsTab.activeSkillSetId,
		skillSets = api.array(),
		groups = api.array(),
	}
	for _, id in ipairs(skillsTab.skillSetOrderList) do
		result.skillSets[#result.skillSets + 1] = { id = id, title = skillsTab.skillSets[id].title or "Default" }
	end
	for i, group in ipairs(skillsTab.socketGroupList) do
		local active, skills = { }, api.array()
		for k, activeSkill in ipairs(group.displaySkillList or { }) do
			for _, effect in ipairs(activeSkill.effectList or { }) do
				if effect.srcInstance then
					active[effect.srcInstance] = true
				end
			end
			if activeSkill.minion and activeSkill.minion.mainSkill then
				local minionEffect = activeSkill.minion.mainSkill.effectList[1]
				if minionEffect and minionEffect.srcInstance then
					active[minionEffect.srcInstance] = true
				end
			end
			local flags = activeSkill.activeEffect.statSet and activeSkill.activeEffect.statSet.skillFlags or { }
			skills[k] = {
				name = calcs.getActiveSkillDisplayName(activeSkill),
				disabled = flags.disable or false,
				disableReason = activeSkill.disableReason,
			}
		end
		local gems = api.array()
		for j, gem in ipairs(group.gemList) do
			local effect = effectOf(gem)
			local display = gem.displayEffect
			local status, reason = gemStatus(group, gem, active)
			gems[j] = {
				name = (gem.grantedEffect and gem.grantedEffect.name) or (gem.gemData and gem.gemData.name) or gem.nameSpec,
				level = gem.level,
				quality = gem.quality,
				effectiveLevel = display and display.level or nil,
				effectiveQuality = display and display.quality or nil,
				enabled = gem.enabled and true or false,
				count = gem.count or 1,
				corrupted = gem.corrupted and true or false,
				corruptLevel = gem.corruptLevel or 0,
				colour = effect and colourCodes[effect.color] or (gem.color or colorCodes.NORMAL),
				support = effect and effect.support or false,
				errMsg = gem.errMsg,
				status = status,
				reason = reason,
				locked = isLockedGem(group, j),
			}
		end
		local source = group.sourceItem or group.sourceNode
		result.groups[i] = {
			label = group.label or "",
			displayLabel = group.displayLabel or group.label or "",
			enabled = group.enabled and true or false,
			includeInFullDPS = group.includeInFullDPS and true or false,
			isMain = i == build.mainSocketGroup,
			source = group.source,
			sourceName = source and (source.name or source.dn) or nil,
			deletable = group.source == nil,
			set1 = group.set1 and true or false,
			set2 = group.set2 and true or false,
			weaponSet = skillsTab:GetSocketGroupWeaponSetLabel(group),
			weaponSetLocked = (skillsTab:IsSocketGroupWeaponSetLocked(group) or group.forcedBoth) and true or false,
			groupCount = group.groupCount,
			activeSkills = skills,
			gems = gems,
		}
	end
	return result
end

local function group(i)
	local g = build.skillsTab.socketGroupList[tonumber(i)]
	if not g then
		error("unknown skill group " .. tostring(i), 0)
	end
	return g
end

local function gemOf(i, j)
	local g = group(i)
	local gem = g.gemList[tonumber(j)]
	if not gem then
		error("unknown gem " .. tostring(j), 0)
	end
	return g, gem
end

-- Pastes a group in PoB's text format ("Fireball 20/0  1" per line). PasteSocketGroup reads the
-- clipboard first and only uses its argument without one.
local function pasteGroup(text)
	local paste = Paste
	Paste = function() end
	local ok, err = pcall(build.skillsTab.PasteSocketGroup, build.skillsTab, text)
	Paste = paste
	if not ok then
		error(err, 0)
	end
end

-- Edits skills. args: { op, group, gem, value, ... }; returns { skills, state }
function api.skillEdit(args)
	local skillsTab = build.skillsTab
	local op = args.op
	if op == "groupEnabled" then
		group(args.group).enabled = args.value and true or false
	elseif op == "groupFullDPS" then
		group(args.group).includeInFullDPS = args.value and true or false
	elseif op == "setMain" then
		group(args.group)
		build.mainSocketGroup = tonumber(args.group)
	elseif op == "groupLabel" then
		local g = group(args.group)
		g.label = tostring(args.value or "")
		skillsTab:ProcessSocketGroup(g)
	elseif op == "groupWeaponSets" then
		local g = group(args.group)
		if skillsTab:IsSocketGroupWeaponSetLocked(g) or g.forcedBoth or not (args.set1 or args.set2) then
			error("the weapon sets of this group cannot be changed", 0)
		end
		g.set1, g.set2 = args.set1 and true or false, args.set2 and true or false
	elseif op == "groupCount" then
		group(args.group).groupCount = tonumber(args.value) or 1
	elseif op == "newGroup" then
		local g = { label = "", enabled = true, gemList = { } }
		table.insert(skillsTab.socketGroupList, g)
		skillsTab:ProcessSocketGroup(g)
	elseif op == "pasteGroup" then
		pasteGroup(tostring(args.value or ""))
	elseif op == "deleteGroup" then
		local i = tonumber(args.group)
		local g = group(i)
		if g.source then
			error("groups granted by items or passives cannot be deleted", 0)
		end
		table.remove(skillsTab.socketGroupList, i)
		if skillsTab.displayGroup == g then
			skillsTab:SetDisplayGroup()
		end
		if (build.mainSocketGroup or 1) > i then
			build.mainSocketGroup = build.mainSocketGroup - 1
		end
		local input = build.calcsTab.input
		if (input.skill_number or 1) > i then
			input.skill_number = input.skill_number - 1
		end
	elseif op == "moveGroup" then
		local from, to = tonumber(args.group), tonumber(args.value)
		local list = skillsTab.socketGroupList
		if not list[from] or to < 1 or to > #list then
			error("invalid move", 0)
		end
		table.insert(list, to, table.remove(list, from))
		local function fix(v)
			if v == from then
				return to
			elseif v > from and v <= to then
				return v - 1
			elseif v < from and v >= to then
				return v + 1
			end
			return v
		end
		build.mainSocketGroup = fix(build.mainSocketGroup)
		build.calcsTab.input.skill_number = fix(build.calcsTab.input.skill_number)
	elseif op == "setGem" then
		-- Adds (gem = #gemList + 1) or replaces a gem, by data.gems id
		local g = group(args.group)
		local j = tonumber(args.gem) or (#g.gemList + 1)
		if isLockedGem(g, j) then
			error("this gem cannot be changed", 0)
		end
		local gemData = build.data.gems[args.value]
		if not gemData then
			error("unknown gem " .. tostring(args.value), 0)
		end
		local gem = g.gemList[j]
		if not gem then
			gem = {
				nameSpec = "", level = 1, quality = skillsTab.defaultGemQuality or 0, enabled = true,
				enableGlobal1 = true, enableGlobal2 = true, count = 1, new = true, corrupted = false, corruptLevel = 0,
			}
			g.gemList[#g.gemList + 1] = gem
		end
		if gem.gemId ~= args.value then
			gem.gemId = args.value
			gem.skillId = nil
			skillsTab:ProcessSocketGroup(g)
			gem.level = skillsTab:ProcessGemLevel(gem.gemData)
			gem.naturalMaxLevel = gem.level
			if skillsTab.defaultCorruptionLevel == 1 then
				gem.corrupted = true
				gem.corruptLevel = 1
			end
			skillsTab:ProcessSocketGroup(g)
		end
	elseif op == "removeGem" then
		local g = group(args.group)
		local j = tonumber(args.gem)
		if isLockedGem(g, j) then
			error("this gem cannot be removed", 0)
		end
		gemOf(args.group, j)
		table.remove(g.gemList, j)
	elseif op == "gemEnabled" then
		local g, gem = gemOf(args.group, args.gem)
		if not (gem.gemData and gem.gemData.vaalGem) then
			gem.enableGlobal1 = true
			gem.enableGlobal2 = true
		end
		gem.enabled = args.value and true or false
		skillsTab:ProcessSocketGroup(g)
	elseif op == "gemLevel" then
		local g, gem = gemOf(args.group, args.gem)
		gem.level = tonumber(args.value) or gem.naturalMaxLevel or 20
		skillsTab:ProcessSocketGroup(g)
	elseif op == "gemQuality" then
		local g, gem = gemOf(args.group, args.gem)
		gem.quality = math.max(0, math.min(99, tonumber(args.value) or 0))
		skillsTab:ProcessSocketGroup(g)
	elseif op == "gemCount" then
		local g, gem = gemOf(args.group, args.gem)
		gem.count = math.max(1, tonumber(args.value) or 1)
		skillsTab:ProcessSocketGroup(g)
	elseif op == "gemCorruption" then
		local g, gem = gemOf(args.group, args.gem)
		gem.corrupted = args.value and true or false
		gem.corruptLevel = gem.corrupted and (tonumber(args.corruptLevel) or 0) or 0
		skillsTab:ProcessSocketGroup(g)
	elseif op == "skillSet" then
		skillsTab:SetActiveSkillSet(tonumber(args.value))
	else
		error("unknown skill edit " .. tostring(op), 0)
	end
	if op ~= "pasteGroup" then
		skillsTab:AddUndoState()
	end
	api.dirty()
	api.recalc()
	return { skills = api.skills(), state = api.state() }
end

-- Gems for the "add gem" picker. args: { query, support = true|false|nil }
function api.gemSearch(args)
	local query = (args.query or ""):lower()
	local results = { }
	for id, gemData in pairs(build.data.gems) do
		local effect = gemData.grantedEffect
		if effect and not effect.fromItem and not effect.fromTree and not effect.legacy
			and (args.support == nil or (effect.support and true or false) == args.support) then
			local name = gemData.name or effect.name
			if query == "" or name:lower():find(query, 1, true) then
				results[#results + 1] = {
					gemId = id,
					name = name,
					support = effect.support and true or false,
					colour = colourCodes[effect.color] or colorCodes.NORMAL,
					tags = gemData.tagString,
					starts = name:lower():sub(1, #query) == query,
				}
			end
		end
	end
	table.sort(results, function(a, b)
		if a.starts ~= b.starts then
			return a.starts
		end
		return a.name < b.name
	end)
	local out = api.array()
	for i = 1, math.min(#results, tonumber(args.limit) or 60) do
		results[i].starts = nil
		out[i] = results[i]
	end
	return out
end

-- PoB's gem tooltip lines. args: { group, gem }
function api.gemTooltip(args)
	local _, gem = gemOf(args.group, args.gem)
	local tooltip = new("Tooltip"):Tooltip()
	local GemTooltip = LoadModule("Classes/GemTooltip")
	local ok, err = pcall(GemTooltip.AddGemTooltip, tooltip, build, gem, { })
	if not ok then
		error(err, 0)
	end
	local lines = api.array()
	for _, line in ipairs(tooltip.lines) do
		lines[#lines + 1] = line.text or ""
	end
	return lines
end
