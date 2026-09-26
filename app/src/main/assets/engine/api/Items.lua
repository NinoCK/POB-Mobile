-- The Items tab (ItemsTab.lua): item sets, slots, PoB's item tooltips, and equipping / adding /
-- removing items the way PoB's controls do. Tree jewel sockets follow the app's tree.

-- Records what PoB writes into a tooltip, without wrapping or images
local recorderClass = { }
recorderClass.__index = recorderClass
local function newRecorder()
	local tt = setmetatable({ }, recorderClass)
	tt:Clear()
	return tt
end
function recorderClass:Clear()
	self.lines = { }
	self.tooltipHeader = false
	self.center = false
	self.color = nil
	self.childTooltips = self.childTooltips or { }
end
function recorderClass:AddLine(size, text, font, background, modLine)
	if not text then
		return
	end
	for line in (text .. "\n"):gmatch("([^\n]*)\n") do
		self.lines[#self.lines + 1] = { text = line, size = size, font = font }
	end
end
function recorderClass:AddSeparator(size)
	local last = self.lines[#self.lines]
	if last and last.separator then
		return
	end
	self.lines[#self.lines + 1] = { separator = true }
end
function recorderClass:AddBuildPlannerNote(size, text, prefix)
	self:AddLine(size, (prefix or "") .. text)
end
function recorderClass:CheckForUpdate()
	self:Clear()
	return true
end
function recorderClass:SetRecipe(recipe)
	self.recipe = recipe
end

local function itemLabel(item)
	return (colorCodes[item.rarity] or colorCodes.NORMAL) .. item.name
end

local function slotShown(slot)
	if slot.inactive then
		return false
	end
	local ok, shown = pcall(function()
		return slot:GetProperty("shown")
	end)
	-- PoB's shown functions may return nil for hidden (e.g. Ring 3 without an extra ring slot)
	return not ok or (shown and true or false)
end

function api.items()
	local itemsTab = build.itemsTab
	local result = {
		activeItemSetId = itemsTab.activeItemSetId,
		useSecondWeaponSet = itemsTab.activeItemSet.useSecondWeaponSet and true or false,
		weaponSet = build.calcsTab.mainEnv and build.calcsTab.mainEnv.weaponSet or (itemsTab.activeItemSet.useSecondWeaponSet and 2 or 1),
		itemSets = api.array(),
		slots = api.array(),
		items = api.array(),
	}
	for _, id in ipairs(itemsTab.itemSetOrderList) do
		result.itemSets[#result.itemSets + 1] = { id = id, title = itemsTab.itemSets[id].title or "Default" }
	end
	local equippedIn = { }
	for _, slot in ipairs(itemsTab.orderedSlots) do
		if slotShown(slot) then
			local item = itemsTab.items[slot.selItemId]
			local entry = {
				name = slot.slotName,
				label = slot.label or slot.slotName,
				itemId = item and slot.selItemId or 0,
				item = item and itemLabel(item) or nil,
				nodeId = slot.nodeId,
				candidates = api.array(),
			}
			if slot.controls and slot.controls.activate then
				entry.canActivate = true
				entry.active = slot.active and true or false
			end
			for _, id in ipairs(slot.items or { }) do
				if id ~= 0 and itemsTab.items[id] then
					entry.candidates[#entry.candidates + 1] = id
				end
			end
			if item then
				equippedIn[slot.selItemId] = equippedIn[slot.selItemId] or { }
				table.insert(equippedIn[slot.selItemId], slot.label or slot.slotName)
			end
			result.slots[#result.slots + 1] = entry
		end
	end
	for _, id in ipairs(itemsTab.itemOrderList) do
		local item = itemsTab.items[id]
		if item then
			result.items[#result.items + 1] = {
				id = id,
				name = itemLabel(item),
				base = item.baseName,
				type = item.type,
				rarity = item.rarity,
				equipped = equippedIn[id] and api.array(equippedIn[id]) or api.array(),
			}
		end
	end
	return result
end

-- PoB's tooltip for an item, as lines with colour codes. args: { id, slot? }
-- Includes the stat changes of equipping it in that slot (or removing it, when equipped there).
function api.itemTooltip(args)
	local itemsTab = build.itemsTab
	local item = itemsTab.items[tonumber(args.id)]
	if not item then
		error("unknown item " .. tostring(args.id), 0)
	end
	local slot = args.slot and itemsTab.slots[args.slot] or nil
	local tt = newRecorder()
	local ok, err = pcall(itemsTab.AddItemTooltip, itemsTab, tt, item, slot)
	if not ok then
		error(err, 0)
	end
	local lines = api.array()
	for _, line in ipairs(tt.lines) do
		lines[#lines + 1] = line.separator and { separator = true } or { text = line.text, size = line.size }
	end
	return { title = itemLabel(item), rarity = item.rarity, lines = lines, raw = item:BuildRaw() }
end

-- After item changes: sockets and slot validity (the Items tab does this every frame), then the
-- calculation; PopulateSlots can unequip items that are no longer valid, which needs another pass.
local function refresh()
	local itemsTab = build.itemsTab
	itemsTab:UpdateSockets()
	itemsTab:PopulateSlots()
	api.dirty()
	api.recalc()
	local before = { }
	for name, slot in pairs(itemsTab.slots) do
		before[name] = slot.selItemId
	end
	itemsTab:PopulateSlots()
	for name, slot in pairs(itemsTab.slots) do
		if before[name] ~= slot.selItemId then
			api.dirty()
			api.recalc()
			break
		end
	end
end

-- Edits items. args: { op, ... }; returns { items, state }
--   equip { slot, id }          id 0 unequips
--   activate { slot, value }    flask / charm in use
--   itemSet { id }              switch the active item set
--   weaponSet { value }         true = weapon set II
--   add { text, equip }         parse item text (game or PoB format), optionally equip it
--   delete { id }
function api.itemEdit(args)
	local itemsTab = build.itemsTab
	local op = args.op
	local result = { }
	if op == "equip" then
		local slot = itemsTab.slots[args.slot]
		if not slot then
			error("unknown slot " .. tostring(args.slot), 0)
		end
		local id = tonumber(args.id) or 0
		local item = itemsTab.items[id]
		if id ~= 0 and not (item and itemsTab:IsItemValidForSlot(item, args.slot)) then
			error("this item cannot be used in " .. (slot.label or args.slot), 0)
		end
		if slot.selItemId ~= id then
			slot:SetSelItemId(id)
			itemsTab:PopulateSlots()
			itemsTab:AddUndoState()
		end
	elseif op == "activate" then
		local slot = itemsTab.slots[args.slot]
		if not slot then
			error("unknown slot " .. tostring(args.slot), 0)
		end
		slot.active = args.value and true or false
		if itemsTab.activeItemSet[args.slot] then
			itemsTab.activeItemSet[args.slot].active = slot.active
		end
		if slot.controls and slot.controls.activate then
			slot.controls.activate.state = slot.active
		end
		itemsTab:AddUndoState()
	elseif op == "itemSet" then
		itemsTab:SetActiveItemSet(tonumber(args.id))
		itemsTab:AddUndoState()
	elseif op == "weaponSet" then
		itemsTab.activeItemSet.useSecondWeaponSet = args.value and true or false
		itemsTab:AddUndoState()
	elseif op == "add" then
		local text = tostring(args.text or ""):gsub("\r\n", "\n")
		local item = new("Item"):Item(text)
		if not item.base then
			error("Unrecognised item: paste the text of an item copied from the game or Path of Building", 0)
		end
		item:NormaliseQuality()
		item:BuildModList()
		itemsTab:AddItem(item, not args.equip)
		itemsTab:PopulateSlots()
		itemsTab:AddUndoState()
		result.addedId = item.id
	elseif op == "delete" then
		local id = tonumber(args.id)
		local item = itemsTab.items[id]
		if not item then
			error("unknown item " .. tostring(args.id), 0)
		end
		-- The app owns the passive tree: take the jewel out of its sockets first, otherwise PoB also
		-- removes the socket and the passives depending on it
		for _, spec in pairs(build.treeTab.specList) do
			for nodeId, itemId in pairs(spec.jewels) do
				if itemId == id then
					spec.jewels[nodeId] = 0
				end
			end
		end
		for nodeId, slot in pairs(itemsTab.sockets) do
			if slot.selItemId == id then
				slot:SetSelItemId(0)
			end
		end
		itemsTab:DeleteItem(item)
	else
		error("unknown item edit " .. tostring(op), 0)
	end
	refresh()
	result.items = api.items()
	result.state = api.state()
	return result
end
