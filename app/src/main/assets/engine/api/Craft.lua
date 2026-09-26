-- Item crafting and editing with PoB's own item editor: the Items tab's "display item" (Craft
-- item..., uniques and rare templates, editing an item) with its controls, and the popups they open
-- (base selection, Add modifier, Anoint, Corrupt, item text). The app shows the controls PoB shows
-- and passes the choices to them, so the crafting rules are PoB's.

local function itemsTab()
	return build.itemsTab
end

-- Text of a dropdown / list entry
local function entryLabel(v)
	if type(v) == "table" then
		return tostring(v.label or v.name or v.dn or v[1] or "")
	end
	return tostring(v)
end

local function isShown(control)
	local ok, shown = pcall(control.IsShown, control)
	return ok and shown and true or false
end

-- A control as JSON: { name, kind, enabled, ... }; nil for controls the app does not show
local function describe(name, c)
	local cls = c._className
	local enabled = true
	pcall(function()
		enabled = c:IsEnabled() ~= false
	end)
	local d = { name = tostring(name), enabled = enabled }
	if cls == "DropDownControl" then
		d.kind = "dropdown"
		d.options = api.array()
		for i, v in ipairs(c.list or { }) do
			d.options[i] = entryLabel(v)
		end
		d.selected = c.selIndex
		d.detail = c.tooltipFunc ~= nil
	elseif cls == "EditControl" then
		d.kind = "edit"
		d.text = c.buf or ""
		d.prompt = c.prompt
		d.numeric = c.filter == "%D"
		d.multiline = c.lineHeight ~= nil
	elseif cls == "SliderControl" then
		d.kind = "slider"
		d.value = c.val or 0
		d.steps = c.divCount
	elseif cls == "ButtonControl" then
		d.kind = "button"
		d.label = c:GetProperty("label") or ""
	elseif cls == "CheckBoxControl" then
		d.kind = "check"
		d.state = c.state and true or false
		d.label = c:GetProperty("label") or ""
	elseif cls == "LabelControl" then
		d.kind = "label"
		d.label = c:GetProperty("label") or ""
		if StripEscapes(d.label):match("^%s*$") then
			return nil
		end
	elseif c.list and (c.OnSelect or c.selValue ~= nil or c.GetRowValue) then
		-- List controls (e.g. the notables to anoint): the rows' text
		d.kind = "list"
		d.options = api.array()
		for i, v in ipairs(c.list) do
			local text
			if c.GetRowValue then
				local ok, value = pcall(c.GetRowValue, c, 1, i, v)
				text = ok and value or nil
			end
			d.options[i] = tostring(text or entryLabel(v))
		end
		d.selected = c.selIndex
	else
		return nil
	end
	return d
end

-- The editor's controls, in rows as PoB lays them out
local function editorRows()
	local rows = {
		{ "addDisplayItem", "editDisplayItem", "removeDisplayItem" },
		{ "displayItemVersion" }, { "displayItemBaseVariant" }, { "displayItemVariant" },
		{ "displayItemAltVariant" }, { "displayItemAltVariant2" }, { "displayItemAltVariant3" }, { "displayItemAltVariant4" }, { "displayItemAltVariant5" },
		{ "displayItemSocketRune", "displayItemSocketRuneEdit", "displayItemSocketJewel", "displayItemSocketJewelEdit" },
		{ "displayItemAnoint", "displayItemAnoint2", "displayItemAnoint3", "displayItemAnoint4", "displayItemCorrupt" },
		{ "displayItemQuality", "displayItemQualityEdit" },
		{ "displayItemCatalyst", "displayItemCatalystQualityEdit" },
	}
	for i = 1, 6 do
		rows[#rows + 1] = { "displayItemRuneLabel" .. i, "displayItemRune" .. i }
	end
	rows[#rows + 1] = { "craftingSortingLabel", "craftingSorting" }
	for i = 1, 9 do
		rows[#rows + 1] = { "displayItemAffixLabel" .. i, "displayItemAffix" .. i }
		rows[#rows + 1] = { "displayItemAffixRangeLabel" .. i, "displayItemAffixRange" .. i }
	end
	rows[#rows + 1] = { "displayItemAddCustom" }
	local i = 1
	while itemsTab().controls["displayItemCustomModifierRemove" .. i] do
		rows[#rows + 1] = { "displayItemCustomModifierLabel" .. i, "displayItemCustomModifier" .. i, "displayItemCustomModifierRemove" .. i }
		i = i + 1
	end
	rows[#rows + 1] = { "displayItemRangeLine", "displayItemRangeSlider" }
	for j = 1, 20 do
		rows[#rows + 1] = { "displayItemStackedRangeSlider" .. j, "displayItemStackedRangeLine" .. j }
	end
	return rows
end

local function describeRows(rows, controls)
	local out = api.array()
	for _, row in ipairs(rows) do
		local r = api.array()
		for _, name in ipairs(row) do
			local c = controls[name]
			if c and isShown(c) then
				r[#r + 1] = describe(name, c)
			end
		end
		if #r > 0 then
			out[#out + 1] = r
		end
	end
	return out
end

-- A popup's controls in rows, by position (controls on the same line form a row)
local function popupRows(popup)
	local entries = { }
	for name, c in pairs(popup.controls) do
		if type(c) == "table" and c._className and isShown(c) then
			local ok, x, y = pcall(c.GetPos, c)
			entries[#entries + 1] = { name = name, control = c, x = ok and x or 0, y = ok and y or 0 }
		end
	end
	table.sort(entries, function(a, b)
		if math.abs(a.y - b.y) > 6 then
			return a.y < b.y
		end
		if a.x ~= b.x then
			return a.x < b.x
		end
		return tostring(a.name) < tostring(b.name)
	end)
	local rows, row, rowY = api.array(), nil, nil
	for _, e in ipairs(entries) do
		local d = describe(e.name, e.control)
		if d then
			if not row or math.abs(e.y - rowY) > 6 then
				row = api.array()
				rows[#rows + 1] = row
				rowY = e.y
			end
			row[#row + 1] = d
		end
	end
	return rows
end

local function tooltipLines(item)
	local tt = api.newTooltipRecorder()
	local ok, err = pcall(itemsTab().AddItemTooltip, itemsTab(), tt, item)
	if not ok then
		error(err, 0)
	end
	return api.tooltipLines(tt)
end

-- The editor: { item = { lines, editing } | nil, rows, popup = { title, rows } | nil, added }
function api.craftState()
	local tab = itemsTab()
	local out = { rows = api.array() }
	local item = tab.displayItem
	if item then
		out.item = { lines = tooltipLines(item), editing = tab.items[item.id] ~= nil, raw = item:BuildRaw() }
		out.rows = describeRows(editorRows(), tab.controls)
	end
	local popup = main.popups[1]
	if popup then
		out.popup = { title = popup.title or "", rows = popupRows(popup) }
	end
	return out
end

-- After an action: the build changed (an item was added or saved) -> recalculate
local function finish(result)
	result = result or api.craftState()
	if build.buildFlag then
		api.refreshItems()
		result.added = true
		result.state = api.state()
	end
	return result
end

local function closePopups()
	while main.popups[1] do
		main:ClosePopup()
	end
end

-- Starts crafting a new item (PoB's "Craft item..." popup)
function api.craftNew()
	closePopups()
	itemsTab():CraftItem()
	return api.craftState()
end

-- Edits an item of the build (like double-clicking it in PoB's item list). args: { id }
function api.craftEdit(args)
	closePopups()
	local tab = itemsTab()
	local item = tab.items[tonumber(args.id)]
	if not item then
		error("unknown item " .. tostring(args.id), 0)
	end
	local newItem = new("Item"):Item(item:BuildRaw())
	newItem.id = item.id
	tab:SetDisplayItem(newItem)
	return api.craftState()
end

-- Starts from item text (PoB's "Create custom..." with the text given). args: { text }
function api.craftFromText(args)
	closePopups()
	local text = tostring(args.text or ""):gsub("\r\n", "\n")
	itemsTab():CreateDisplayItemFromRaw(text, true)
	if not itemsTab().displayItem then
		error("Unrecognised item: paste the text of an item copied from the game or Path of Building", 0)
	end
	return api.craftState()
end

-- PoB loads its unique and rare template databases over its first frames
local function ensureItemDBs()
	while main.onFrameFuncs["LoadItems"] do
		main.onFrameFuncs["LoadItems"]()
	end
end

-- Uniques ("UNIQUE") or rare templates ("RARE") matching a search. args: { kind, query, limit }
function api.itemDB(args)
	ensureItemDBs()
	local db = args.kind == "RARE" and main.rareDB or main.uniqueDB
	local query = tostring(args.query or ""):lower()
	local found = { }
	for name, item in pairs(db.list) do
		local text = (name .. " " .. (item.baseName or "") .. " " .. (item.type or "")):lower()
		if query == "" or text:find(query, 1, true) then
			found[#found + 1] = { name = name, base = item.baseName, type = item.type, rarity = item.rarity }
		end
	end
	table.sort(found, function(a, b)
		if a.type ~= b.type then
			return tostring(a.type) < tostring(b.type)
		end
		return a.name < b.name
	end)
	local out = api.array()
	for i = 1, math.min(#found, tonumber(args.limit) or 400) do
		out[i] = found[i]
	end
	return out
end

-- Starts from a unique or rare template (double-clicking it in PoB's database lists). args: { kind, name }
function api.craftFromDB(args)
	ensureItemDBs()
	closePopups()
	local db = args.kind == "RARE" and main.rareDB or main.uniqueDB
	local item = db.list[args.name]
	if not item then
		error("unknown item " .. tostring(args.name), 0)
	end
	itemsTab():CreateDisplayItemFromRaw(item.raw, true)
	return api.craftState()
end

-- Tooltip lines of a dropdown option (e.g. an affix's tiers). args: { target, name, index }
function api.craftDetail(args)
	local controls = args.target == "popup" and main.popups[1] and main.popups[1].controls or itemsTab().controls
	local c = controls[args.name] or controls[tonumber(args.name)]
	if not c or not c.tooltipFunc then
		return api.array()
	end
	local index = tonumber(args.index)
	local tt = api.newTooltipRecorder()
	local ok, err = pcall(c.tooltipFunc, tt, "HOVER", index, c.list and c.list[index])
	if not ok then
		error(err, 0)
	end
	local lines = api.array()
	for _, line in ipairs(tt.lines) do
		lines[#lines + 1] = line.separator and "" or line.text
	end
	return lines
end

-- Operates a control of the editor or of the open popup, as the user would in PoB.
-- args: { target = "editor" | "popup", name, op = "select" | "text" | "value" | "click" | "check", value }
function api.craftAction(args)
	local controls
	if args.target == "popup" then
		local popup = main.popups[1]
		if not popup then
			error("no popup is open", 0)
		end
		controls = popup.controls
	else
		controls = itemsTab().controls
	end
	local c = controls[args.name] or controls[tonumber(args.name)]
	if not c then
		error("unknown control " .. tostring(args.name), 0)
	end
	local op, value = args.op, args.value
	if op == "select" then
		local index = tonumber(value)
		if c._className == "DropDownControl" then
			if index and index ~= c.selIndex and c.list and c.list[index] ~= nil then
				c.selIndex = index
				if c.selFunc then
					c.selFunc(index, c.list[index])
				end
			end
		else
			-- List controls
			c.selIndex = index
			c.selValue = c.list and c.list[index]
			if c.OnSelect then
				c:OnSelect(index, c.selValue)
			end
		end
	elseif op == "text" then
		c:SetText(tostring(value or ""), true)
	elseif op == "value" then
		c:SetVal(math.max(0, math.min(1, tonumber(value) or 0)))
	elseif op == "check" then
		c.state = value and true or false
		if c.changeFunc then
			c.changeFunc(c.state)
		end
	elseif op == "click" then
		c:Click()
	else
		error("unknown action " .. tostring(op), 0)
	end
	return finish()
end

-- Closes the editor (PoB's "Cancel") and any popup
function api.craftCancel()
	closePopups()
	itemsTab():SetDisplayItem()
	return api.craftState()
end
