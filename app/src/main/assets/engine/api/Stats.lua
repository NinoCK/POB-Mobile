-- The build sidebar: stats (Build.lua AddDisplayStatList), warnings, main skill selection and
-- character level. Strings keep PoB's colour codes (^N, ^xRRGGBB); the app renders them.

local function addInfo(rows, skill)
	if not (skill and skill.infoMessage) then
		return
	end
	local msg = skill.infoMessage
	if #msg > 40 then
		for part in msg:gmatch("([^:]+)") do
			rows[#rows + 1] = { type = "info", text = colorCodes.CUSTOM .. part }
		end
	else
		rows[#rows + 1] = { type = "info", text = colorCodes.CUSTOM .. msg }
	end
	if skill.infoMessage2 then
		rows[#rows + 1] = { type = "info", text = "^8" .. skill.infoMessage2 }
	end
end

-- Mirror of buildMode:AddDisplayStatList that also returns the raw numbers
local function addStats(rows, statList, actor, actorName)
	local o = actor.output
	local flags = actor.mainSkill.activeEffect.statSet.skillFlags
	for _, sd in ipairs(statList) do
		if not sd.flag or flags[sd.flag] then
			local labelColor = sd.color or "^7"
			if sd.stat then
				local v = o[sd.stat]
				if v and sd.childStat then
					v = v[sd.childStat]
				end
				if v and ((sd.condFunc and sd.condFunc(v, o)) or (not sd.condFunc and v ~= 0)) then
					if sd.stat == "SkillDPS" then
						table.sort(v, function(a, b)
							return (a.dps * a.count) > (b.dps * b.count)
						end)
						local overCap = sd.overCapStat and o[sd.overCapStat] or nil
						for _, s in ipairs(v) do
							local count = s.count or 1
							local trigger = ""
							if s.trigger and s.trigger ~= "" then
								trigger = colorCodes.WARNING .. " (" .. s.trigger .. ")" .. colorCodes.CUSTOM
							end
							local label = colorCodes.CUSTOM .. (count ~= 1 and string.format("%g", count) .. "x " or "") .. s.name .. trigger
							rows[#rows + 1] = {
								type = "stat", actor = actorName, stat = "SkillDPS",
								label = label, value = build:FormatStat({ fmt = "1.f" }, s.dps * count, overCap), raw = s.dps * count,
							}
							if s.skillPart then
								rows[#rows + 1] = { type = "info", text = "^8" .. s.skillPart }
							end
							if s.source then
								rows[#rows + 1] = { type = "info", text = colorCodes.WARNING .. "from " .. s.source }
							end
						end
					elseif not sd.hideStat then
						local warn = sd.warnFunc and sd.warnFunc(v, o)
						local override = (o[sd.stat .. "Warning"] or (warn and sd.warnColor)) and colorCodes.NEGATIVE or nil
						local overCap = sd.overCapStat and o[sd.overCapStat] or nil
						local text = build:FormatStat(sd, v, overCap, override)
						if sd.suffix and (not sd.suffixCondFunc or sd.suffixCondFunc(v, o)) then
							local suffix = type(sd.suffix) == "function" and sd.suffix(v, o) or sd.suffix
							if suffix then
								text = text .. "^x808080 (" .. suffix .. ")"
							end
						end
						rows[#rows + 1] = {
							type = "stat", actor = actorName, stat = sd.stat, childStat = sd.childStat,
							label = labelColor .. sd.label, value = text,
							raw = type(v) == "number" and v or nil,
							warn = override ~= nil,
							breakdown = build:GetStatBreakdownKey(sd, actorName) ~= nil,
						}
					end
				end
			elseif sd.label and sd.condFunc and sd.condFunc(o) then
				rows[#rows + 1] = {
					type = "stat", actor = actorName, stat = sd.labelStat,
					label = labelColor .. sd.label,
					value = "^7" .. tostring(o[sd.labelStat]) .. "%^x808080 (" .. tostring(sd.val) .. ")",
					raw = type(o[sd.labelStat]) == "number" and o[sd.labelStat] or nil,
				}
			elseif #rows == 0 or rows[#rows].type ~= "sep" then
				rows[#rows + 1] = { type = "sep" }
			end
		end
	end
end

function api.sidebar()
	local env = build.calcsTab.mainEnv
	local rows = { }
	if env then
		addInfo(rows, env.player.mainSkill)
		if env.minion then
			rows[#rows + 1] = { type = "header", text = "^7Minion:" }
			addInfo(rows, env.minion.mainSkill)
			addStats(rows, build.minionDisplayStats, env.minion, "minion")
			rows[#rows + 1] = { type = "header", text = "^7Player:" }
		end
		local mainSkill = env.player.mainSkill
		if mainSkill.activeEffect.statSet.skillFlags.disable then
			rows[#rows + 1] = { type = "header", text = "^7Skill disabled:" }
			rows[#rows + 1] = { type = "info", text = mainSkill.disableReason or "" }
		end
		addStats(rows, build.displayStats, env.player, "player")
	end
	local warnings = { }
	for _, line in ipairs(build.controls.warnings.lines or { }) do
		warnings[#warnings + 1] = line
	end
	return { rows = api.array(rows), warnings = api.array(warnings) }
end

---------------------------------------------------------------------------------------------------
-- Main skill selection (the sidebar dropdowns, Build.lua RefreshSkillSelectControls)
---------------------------------------------------------------------------------------------------

local function mainGroup()
	return build.skillsTab.socketGroupList[build.mainSocketGroup]
end

local function mainActive()
	local group = mainGroup()
	if not group or not group.displaySkillList then
		return nil
	end
	return group.displaySkillList[group.mainActiveSkill or 1]
end

function api.selection()
	local skillsTab = build.skillsTab
	local sel = {
		mainSocketGroup = build.mainSocketGroup,
		groups = api.array(),
		useSecondWeaponSet = build.itemsTab.activeItemSet and build.itemsTab.activeItemSet.useSecondWeaponSet or false,
	}
	for i, group in ipairs(skillsTab.socketGroupList) do
		sel.groups[i] = {
			label = group.displayLabel or group.label or "",
			enabled = group.enabled and true or false,
			includeInFullDPS = group.includeInFullDPS and true or false,
			weaponSet = skillsTab:GetSocketGroupWeaponSetLabel(group),
			source = group.source,
		}
	end
	local group, active = mainGroup(), mainActive()
	if group then
		sel.mainActiveSkill = group.mainActiveSkill or 1
		sel.activeSkills = api.array()
		for i, activeSkill in ipairs(group.displaySkillList or { }) do
			sel.activeSkills[i] = build.calcsTab.calcs.getActiveSkillDisplayName(activeSkill)
		end
	end
	if active then
		local effect = active.activeEffect
		local granted, src = effect.grantedEffect, effect.srcInstance
		local flags = effect.statSet and effect.statSet.skillFlags or { }
		if granted.statSets and #granted.statSets > 0 then
			sel.statSets = api.array()
			for i, statSet in ipairs(granted.statSets) do
				sel.statSets[i] = statSet.label
			end
			sel.statSet = src.statSet and src.statSet[granted.id] or 1
		end
		if granted.parts and #granted.parts > 1 then
			sel.parts = api.array()
			for i, part in ipairs(granted.parts) do
				sel.parts[i] = part.name
			end
			sel.skillPart = src.skillPart or 1
			local part = granted.parts[sel.skillPart]
			if part and part.stages then
				sel.stageCount = src.skillStageCount or part.stagesMin or 1
			end
		end
		if flags.multiStage and not (granted.parts and #granted.parts > 1) then
			sel.stageCount = src.skillStageCount or (active.skillData and active.skillData.stagesMin) or 1
		end
		if flags.mine then
			sel.mineCount = src.skillMineCount
			sel.hasMineCount = true
		end
		local minionList = active.minionList or granted.minionList
		if not flags.disable and (granted.minionList or (minionList and minionList[1])) then
			sel.minions = api.array()
			if granted.minionHasItemSet then
				for _, id in ipairs(build.itemsTab.itemSetOrderList) do
					sel.minions[#sel.minions + 1] = { label = build.itemsTab.itemSets[id].title or "Default Item Set", itemSetId = id }
				end
				sel.minionItemSetId = src.skillMinionItemSet or 1
			else
				for _, id in ipairs(minionList or { }) do
					local minion = build.data.minions[id]
					if minion then
						sel.minions[#sel.minions + 1] = { label = minion.name, minionId = id }
					end
				end
				sel.minionId = src.skillMinion or (minionList and minionList[1])
			end
			if active.minion and active.minion.activeSkillList and active.minion.activeSkillList[1] then
				sel.minionSkills = api.array()
				for i, minionSkill in ipairs(active.minion.activeSkillList) do
					sel.minionSkills[i] = minionSkill.activeEffect.grantedEffect.name
				end
				sel.minionSkill = src.skillMinionSkill or 1
				local minionSkill = active.minion.activeSkillList[sel.minionSkill]
				if minionSkill and minionSkill.activeEffect.grantedEffect.statSets and #minionSkill.activeEffect.grantedEffect.statSets > 1 then
					sel.minionSkillStatSets = api.array()
					for i, statSet in ipairs(minionSkill.activeEffect.grantedEffect.statSets) do
						sel.minionSkillStatSets[i] = statSet.label
					end
					local lookup = src.skillMinionSkillStatSetIndexLookup
					sel.minionSkillStatSet = lookup and lookup[granted.id] and lookup[granted.id][sel.minionSkill] or 1
				end
			end
		end
	end
	return sel
end

-- The sidebar state the app shows after every change
function api.state()
	return {
		sidebar = api.sidebar(),
		selection = api.selection(),
		level = build.characterLevel,
		levelAuto = build.characterLevelAutoMode and true or false,
		mainSkillLabel = build.controls.mainSkillLabel and build.controls.mainSkillLabel.label or nil,
		undo = api.undoState(),
		overlay = api.treeOverlay(),
	}
end

-- Changes one field of the main skill selection, recalculates and returns the new state.
-- args: { mainSocketGroup | mainActiveSkill | statSet | skillPart | stageCount | mineCount |
--         minionId | minionItemSetId (+ label) | minionSkill | minionSkillStatSet | useSecondWeaponSet }
function api.select(args)
	local active = mainActive()
	local src = active and active.activeEffect.srcInstance
	local granted = active and active.activeEffect.grantedEffect
	if args.mainSocketGroup then
		build.mainSocketGroup = args.mainSocketGroup
	elseif args.mainActiveSkill then
		mainGroup().mainActiveSkill = args.mainActiveSkill
	elseif args.statSet and src then
		src.statSet = src.statSet or { }
		src.statSet[granted.id] = args.statSet
	elseif args.skillPart and src then
		src.skillPart = args.skillPart
	elseif args.stageCount ~= nil and src then
		src.skillStageCount = tonumber(args.stageCount)
	elseif args.mineCount ~= nil and src then
		-- A negative count clears it (PoB then uses the default)
		local count = tonumber(args.mineCount)
		src.skillMineCount = count and count >= 0 and count or nil
	elseif (args.minionId or args.minionItemSetId) and src then
		if args.minionItemSetId then
			src.skillMinionItemSet = args.minionItemSetId
			src.skillMinionItemSetCalcs = args.minionItemSetId
		else
			src.skillMinion = args.minionId
			src.skillMinionCalcs = args.minionId
		end
		if args.label and src.nameSpec then
			if src.nameSpec:match("^Spectre:") then
				src.nameSpec = "Spectre: " .. args.label
			elseif src.nameSpec:match("^Companion:") then
				src.nameSpec = "Companion: " .. args.label
			end
		end
	elseif args.minionSkill and src then
		src.skillMinionSkill = args.minionSkill
	elseif args.minionSkillStatSet and src then
		local id = granted.id
		src.skillMinionSkillStatSetIndexLookup = src.skillMinionSkillStatSetIndexLookup or { }
		src.skillMinionSkillStatSetIndexLookup[id] = src.skillMinionSkillStatSetIndexLookup[id] or { }
		src.skillMinionSkillStatSetIndexLookup[id][src.skillMinionSkill or 1] = args.minionSkillStatSet
	elseif args.useSecondWeaponSet ~= nil then
		build.itemsTab.activeItemSet.useSecondWeaponSet = args.useSecondWeaponSet and true or false
		build.itemsTab:AddUndoState()
	end
	api.dirty()
	api.recalc()
	return api.state()
end

-- Character level: { level = n } sets a fixed level, { auto = true } lets PoB estimate it
function api.setLevel(args)
	if args.auto ~= nil then
		build.characterLevelAutoMode = args.auto and true or false
		if build.controls.levelScalingButton then
			build.controls.levelScalingButton.label = build.characterLevelAutoMode and "Auto" or "Manual"
		end
	end
	if args.level then
		build.characterLevel = math.min(math.max(math.floor(tonumber(args.level) or 1), 1), 100)
		build.characterLevelAutoMode = false
		if build.controls.levelScalingButton then
			build.controls.levelScalingButton.label = "Manual"
		end
		if build.controls.characterLevel then
			build.controls.characterLevel:SetText(build.characterLevel)
		end
	end
	build.configTab:BuildModList()
	api.dirty()
	api.recalc()
	return api.state()
end
