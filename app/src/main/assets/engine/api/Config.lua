-- The Configuration tab (ConfigTab.lua / ConfigOptions.lua): the options PoB shows for the
-- current build, their values, and changing them the way PoB's controls do.
-- Rows are identified by their index in ConfigOptions (one option var is used by two rows).

local varList = require("Modules.ConfigOptions") -- the same cached table ConfigTab uses

local function activeSet()
	local configTab = build.configTab
	return configTab.configSets[configTab.activeConfigSetId]
end

local function tooltipText(control, varData)
	local ok, text = pcall(function()
		return control:GetProperty("tooltipText")
	end)
	if ok and text and text ~= "" then
		return text
	end
	if type(varData.tooltip) == "string" then
		return varData.tooltip
	end
end

-- Visible options, grouped by section. args: { showAll = bool }
function api.config(args)
	local configTab = build.configTab
	local set = activeSet()
	local savedSearch, savedToggle = configTab.controls.search.buf, configTab.toggleConfigs
	configTab.controls.search.buf = ""
	configTab.toggleConfigs = args.showAll and true or false
	local collapsed = { }
	local sections = api.array()
	local section
	local sectionIndex, rowIndex = 0, 0
	local ok, err = pcall(function()
		for idx, varData in ipairs(varList) do
			if varData.section then
				sectionIndex, rowIndex = sectionIndex + 1, 0
				local sectionControl = configTab.sectionList[sectionIndex]
				if sectionControl then
					collapsed[sectionControl] = sectionControl.collapsed
					sectionControl.collapsed = false
				end
				section = { name = varData.section, col = varData.col, rows = api.array() }
				sections[#sections + 1] = section
			elseif section then
				rowIndex = rowIndex + 1
				local sectionControl = configTab.sectionList[sectionIndex]
				local control = sectionControl and sectionControl.varControlList[rowIndex]
				if control and control:GetProperty("shown") then
					local row = { idx = idx, var = varData.var, type = varData.var and (varData.type or "label") or "label", label = varData.label }
					if varData.var then
						local current = set.input[varData.var]
						local default = configTab:GetDefaultState(varData.var, type(current))
						row.modified = current ~= nil and current ~= default
						local border = control.borderFunc and control.borderFunc()
						row.invalid = (row.modified and border ~= nil and border > 0.7) or false
						if varData.type == "check" then
							row.value = current and true or false
						elseif varData.type == "list" then
							row.choices = api.array()
							local selected = 1
							for i, entry in ipairs(varData.list) do
								row.choices[i] = entry.label
								if entry.val == current then
									selected = i
								end
							end
							if current == nil then
								selected = varData.defaultIndex or 1
							end
							row.selected = selected
						elseif varData.type == "text" then
							row.text = current and tostring(current) or ""
						else
							row.number = type(current) == "number" and current or nil
							row.placeholder = tonumber(control.placeholder)
						end
						row.enabled = control:GetProperty("enabled") ~= false
					end
					row.tooltip = tooltipText(control, varData)
					section.rows[#section.rows + 1] = row
				end
			end
		end
	end)
	for control, value in pairs(collapsed) do
		control.collapsed = value
	end
	configTab.controls.search.buf, configTab.toggleConfigs = savedSearch, savedToggle
	if not ok then
		error(err, 0)
	end
	-- PoB hides empty sections
	local result = api.array()
	for _, s in ipairs(sections) do
		if #s.rows > 0 then
			result[#result + 1] = s
		end
	end
	local sets = api.array()
	for _, id in ipairs(configTab.configSetOrderList) do
		local s = configTab.configSets[id]
		sets[#sets + 1] = { id = id, title = s.title or "Default", active = id == configTab.activeConfigSetId }
	end
	return { sections = result, sets = sets, customMods = api.customMods() }
end

-- Changes one option. args: { idx, value } — value is a boolean (check), a number or null
-- (numbers; null uses the placeholder), a choice index (list) or text (text).
function api.setConfig(args)
	local configTab = build.configTab
	local varData = varList[tonumber(args.idx)]
	if not (varData and varData.var) then
		error("unknown option " .. tostring(args.idx), 0)
	end
	local value = args.value
	if varData.type == "check" then
		value = value and true or false
	elseif varData.type == "list" then
		local entry = varData.list[tonumber(value)]
		if not entry then
			error("unknown choice " .. tostring(value), 0)
		end
		value = entry.val
	elseif varData.type == "text" then
		value = value ~= nil and tostring(value) or nil
	else
		value = tonumber(value)
		if value then
			if varData.type == "count" then
				value = math.max(0, math.floor(value))
			elseif varData.type == "integer" or varData.type == "countAllowZero" then
				value = math.floor(value)
			elseif varData.type == "float" then
				value = math.max(0, value)
			end
		end
	end
	activeSet().input[varData.var] = value
	configTab:UpdateControls()
	configTab:AddUndoState()
	configTab:BuildModList()
	api.dirty()
	api.recalc()
	return api.config({ showAll = args.showAll })
end

-- Resets an option to its default. args: { idx }
function api.resetConfig(args)
	local varData = varList[tonumber(args.idx)]
	if not (varData and varData.var) then
		error("unknown option " .. tostring(args.idx), 0)
	end
	local configTab = build.configTab
	if varData.type == "check" or varData.type == "list" or varData.type == "text" then
		activeSet().input[varData.var] = configTab.defaultState[varData.var]
	else
		activeSet().input[varData.var] = nil
	end
	configTab:UpdateControls()
	configTab:AddUndoState()
	configTab:BuildModList()
	api.dirty()
	api.recalc()
	return api.config({ showAll = args.showAll })
end

-- Switches the active configuration set. args: { id }
function api.setConfigSet(args)
	local configTab = build.configTab
	configTab:SetActiveConfigSet(tonumber(args.id))
	configTab:AddUndoState()
	api.dirty()
	api.recalc()
	return api.config({ showAll = args.showAll })
end

---------------------------------------------------------------------------------------------------
-- Custom modifiers (per configuration set)
---------------------------------------------------------------------------------------------------

function api.customMods()
	local blocks = api.array()
	for i, block in ipairs(activeSet().customModsList or { }) do
		local lines = api.array()
		for line in ((block.text or "") .. "\n"):gmatch("([^\n]*)\n") do
			local clean = StripEscapes(line):match("^%s*(.-)%s*$")
			local supported = true
			if clean ~= "" then
				local mods, extra = modLib.parseMod(clean)
				supported = mods ~= nil and not extra
			end
			lines[#lines + 1] = { text = line, supported = supported }
		end
		blocks[i] = { title = block.title or "", enabled = block.enabled ~= false, text = block.text or "", lines = lines }
	end
	return blocks
end

-- Replaces the custom modifier blocks. args: { blocks = { { title, enabled, text }, ... } }
function api.setCustomMods(args)
	local configTab = build.configTab
	local list = { }
	for _, block in ipairs(args.blocks or { }) do
		list[#list + 1] = { title = block.title ~= "" and block.title or "Default", enabled = block.enabled ~= false, text = block.text or "" }
	end
	if #list == 0 then
		list[1] = { title = "Default", enabled = true, text = "" }
	end
	activeSet().customModsList = list
	configTab:UpdateCustomModsControls()
	configTab:AddUndoState()
	configTab:BuildModList()
	api.dirty()
	api.recalc()
	return api.customMods()
end
