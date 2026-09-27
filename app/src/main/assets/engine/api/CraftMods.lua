-- The modifiers of the item in PoB's item editor, for the app's crafting screen: every prefix and
-- suffix the item can have, from each source PoB has data for (the base's regular modifiers,
-- essences, desecrated and rune-influenced modifiers, the others of the item class, or any
-- modifier), with their tiers and a roll for each value. The item stays PoB's crafted item: the
-- choices go into its prefixes / suffixes and Item:Craft writes the modifier lines, so the text,
-- the requirements and the calculations are PoB's.

local t_insert = table.insert
local t_sort = table.sort
local m_max = math.max
local m_min = math.min
local m_floor = math.floor

local function itemsTab()
	return build.itemsTab
end

local function displayItem()
	local item = itemsTab().displayItem
	if not item then
		error("no item is being edited", 0)
	end
	return item
end

---------------------------------------------------------------------------------------------------
-- Modifier data
---------------------------------------------------------------------------------------------------

local RANGE = "%((%-?%d+%.?%d*)%-(%-?%d+%.?%d*)%)"
local SIGNED_RANGE = "([%+%-]?)%((%-?%d+%.?%d*)%-(%-?%d+%.?%d*)%)"

local desecratedTags = { unveiled_mod = true, ulaman_mod = true, amanamu_mod = true, kurgal_mod = true }

local function isDesecrated(mod)
	for _, tag in ipairs(mod.modTags or { }) do
		if desecratedTags[tag] then
			return true
		end
	end
	return false
end

local function decimals(text)
	local frac = text:match("%.(%d+)")
	return frac and #frac or 0
end

-- The "(min-max)" ranges of a line, in order: { min, max, decimals } (a "-" before the range negates it)
local function lineRanges(line)
	local out = { }
	for sign, a, b in line:gmatch(SIGNED_RANGE) do
		local lo, hi = tonumber(a), tonumber(b)
		if sign == "-" then
			lo, hi = -lo, -hi
		end
		t_insert(out, { min = lo, max = hi, decimals = m_max(decimals(a), decimals(b)) })
	end
	return out
end

local function rangeCounts(mod)
	local counts, total, maxPerLine = { }, 0, 0
	for i, line in ipairs(mod) do
		local n = 0
		for _ in line:gmatch(RANGE) do
			n = n + 1
		end
		counts[i] = n
		total = total + n
		maxPerLine = m_max(maxPerLine, n)
	end
	return counts, total, maxPerLine
end

-- A table range with one roll for each range of all the modifier's lines, as the app stores it,
-- split into the rolls of each line. PoB's own table ranges (one roll per range of a line, used
-- for every line) are shorter and are left to PoB (nil).
local function splitRanges(mod, range)
	if type(range) ~= "table" then
		return nil
	end
	local counts, total, maxPerLine = rangeCounts(mod)
	if #range ~= total or total <= maxPerLine then
		return nil
	end
	local out, k = { }, 0
	for i = 1, #mod do
		local r = { }
		for j = 1, counts[i] do
			k = k + 1
			r[j] = range[k]
		end
		out[i] = r
	end
	return out
end

-- The roll of each range of the modifier (the app's order: lines, then ranges in a line)
local function valueRolls(mod, range)
	local counts, total = rangeCounts(mod)
	local rolls = { }
	local perLine = splitRanges(mod, range)
	for i = 1, #mod do
		for j = 1, counts[i] do
			local r
			if perLine then
				r = perLine[i][j]
			elseif type(range) == "table" then
				r = range[j] or 0.5
			else
				r = range or main.defaultItemAffixQuality or 0.5
			end
			t_insert(rolls, m_max(0, m_min(1, tonumber(r) or 0.5)))
		end
	end
	return rolls, total
end

-- Rounds like the game shows the value of a range
local function roundTo(value, places)
	local p = 10 ^ places
	return m_floor(value * p + 0.5) / p
end

-- A line with its ranges rolled: PoB's applyRange, with a roll for each range
local function applyLineRolls(line, rolls)
	if #rolls <= 1 then
		return itemLib.applyRange(line, rolls[1] or 0.5)
	end
	local same = true
	for i = 2, #rolls do
		if rolls[i] ~= rolls[1] then
			same = false
		end
	end
	if same then
		return itemLib.applyRange(line, rolls[1])
	end
	local ok, text = pcall(itemLib.applyRange, line, rolls)
	if ok then
		return text
	end
	-- Lines without PoB scaling data: the values in place of the ranges, then PoB's formatting
	local k = 0
	local fixed = line:gsub(RANGE, function(a, b)
		k = k + 1
		local lo, hi = tonumber(a), tonumber(b)
		return tostring(roundTo(lo + (rolls[k] or 0.5) * (hi - lo), m_max(decimals(a), decimals(b))))
	end)
	return itemLib.applyRange(fixed, 1)
end

-- Lines of a modifier with its range(s) applied
local function modLines(mod, range)
	local perLine = splitRanges(mod, range)
	local out = { }
	for i, line in ipairs(mod) do
		if perLine then
			out[i] = applyLineRolls(line, perLine[i])
		else
			out[i] = itemLib.applyRange(line, range or 0.5)
		end
	end
	return out
end

---------------------------------------------------------------------------------------------------
-- Patches (once PoB's classes are loaded)
---------------------------------------------------------------------------------------------------

-- Item.lua's sortCraftedModLines (local there)
local function sortCraftedModLines(modLines)
	local sourceOrder = { }
	for index, modLine in ipairs(modLines) do
		sourceOrder[modLine] = index
	end
	t_sort(modLines, function(a, b)
		local aGroup = (a.crafted or a.custom) and 3 or a.fractured and 1 or 2
		local bGroup = (b.crafted or b.custom) and 3 or b.fractured and 1 or 2
		if aGroup ~= bGroup then
			return aGroup < bGroup
		elseif aGroup < 3 and a.order ~= b.order then
			return (a.order or math.huge) < (b.order or math.huge)
		end
		return sourceOrder[a] < sourceOrder[b]
	end)
end

local patched = false
local function patch()
	if patched then
		return
	end
	patched = true

	-- Desecrated modifiers can be a crafted prefix / suffix: the item classes' affix tables find
	-- them by id (their own entries, which PoB's lists go through, are unchanged)
	local desecrated = { }
	for id, mod in pairs(data.itemMods.Desecrated) do
		if mod.type == "Prefix" or mod.type == "Suffix" then
			desecrated[id] = mod
		end
	end
	for _, key in ipairs({ "Item", "Jewel", "Flask", "Charm", "IncursionLimb" }) do
		local tbl = data.itemMods[key]
		if tbl and not getmetatable(tbl) then
			setmetatable(tbl, { __index = desecrated })
		end
	end

	-- Item:Craft as in Item.lua, plus: a roll for each value of a modifier (the app's table ranges,
	-- see splitRanges), and desecrated modifiers marked as such
	new("Item")
	local ItemClass = common.classes.Item
	function ItemClass:Craft()
		-- Save off any custom mods so they can be re-added at the end
		local savedMods = { }
		for _, mod in ipairs(self.explicitModLines) do
			if mod.custom then
				t_insert(savedMods, mod)
			end
		end

		wipeTable(self.explicitModLines)
		self.namePrefix = ""
		self.nameSuffix = ""
		self.requirements.level = m_max(self.base.req.level or 0, self.requirements.runeLevel)
		local statOrder = { }
		for _, list in ipairs({ self.prefixes, self.suffixes }) do
			for i = 1, (list.limit or (self.affixLimit / 2)) do
				local affix = list[i]
				if not affix then
					affix = { modId = "None" }
					list[i] = affix
				end
				local mod = self.affixes[affix.modId]
				if mod then
					if mod.type == "Prefix" then
						self.namePrefix = (mod.affix or "") .. " " .. self.namePrefix
					elseif mod.type == "Suffix" then
						self.nameSuffix = self.nameSuffix .. " " .. (mod.affix or "")
					end
					self.requirements.level = m_max(self.requirements.level, m_floor(mod.level * 0.8))
					local perLine = splitRanges(mod, affix.range)
					local desecratedMod = isDesecrated(mod) or nil
					for l, line in ipairs(mod) do
						if perLine then
							line = applyLineRolls(line, perLine[l])
						else
							line = itemLib.applyRange(line, affix.range or 0.5)
						end
						local order = mod.statOrder[l]
						if statOrder[order] then
							-- Combine stats
							local start = 1
							statOrder[order].line = statOrder[order].line:gsub("%d+", function(num)
								local s, e, other = line:find("(%d+)", start)
								start = e + 1
								return tonumber(num) + tonumber(other)
							end)
						else
							local modLine = { line = line, order = order, type = mod.type, modTags = mod.modTags or { }, unscalable = mod.unscalable, fractured = affix.fractured, desecrated = desecratedMod }
							modLine[mod.type:lower()] = true
							for n = 1, #self.explicitModLines + 1 do
								if not self.explicitModLines[n] or self.explicitModLines[n].order > order then
									t_insert(self.explicitModLines, n, modLine)
									break
								end
							end
							statOrder[order] = modLine
						end
					end
				end
			end
		end

		-- Restore the custom mods
		for _, mod in ipairs(savedMods) do
			t_insert(self.explicitModLines, mod)
		end
		if #self.explicitModLines > 1 then
			sortCraftedModLines(self.explicitModLines)
		end

		self:BuildAndParseRaw()
	end
end
api.craftPatch = patch

---------------------------------------------------------------------------------------------------
-- Candidate modifiers
---------------------------------------------------------------------------------------------------

local SOURCE_ORDER = { regular = 1, essence = 2, desecrated = 3, influence = 4, other = 5, any = 6 }

local function essenceItemType(item)
	return (item.type == "Staff" and item.base.subType) or item.type
end

local function hasSpawnTag(mod, tag)
	for i, key in ipairs(mod.weightKey or { }) do
		if key == tag and (mod.weightVal[i] or 0) > 0 then
			return true
		end
	end
	return false
end

-- A modifier with zero spawn weight but made for this item class (the base has one of its keys)
local function madeForBase(item, mod)
	for _, key in ipairs(mod.weightKey or { }) do
		if key ~= "default" and item.base.tags[key] then
			return true
		end
	end
	return false
end

local function statOrderKey(mod)
	local parts = { }
	for i, order in ipairs(mod.statOrder or { }) do
		parts[i] = tostring(order)
	end
	return table.concat(parts, ",")
end

local function essenceFamilyName(name)
	return (name:gsub("^Lesser ", ""):gsub("^Greater ", ""):gsub("^Perfect ", ""))
end

-- Candidates for one affix type of the item: list of { modId, mod, source, family, tierName },
-- and the main source of each modifier. Cached by base (the spawn weights depend on its tags).
local candidateCache = { }
local candidateCacheSize = 0

local function buildCandidates(item, affixType, extraTags, withAny)
	local out = { }
	local seen = { }
	local function add(modId, mod, source, family, tierName, sortLevel)
		t_insert(out, { modId = modId, mod = mod, source = source, family = family, tierName = tierName, sortLevel = sortLevel or mod.level or 0 })
		if source ~= "any" and (not seen[modId] or SOURCE_ORDER[source] < SOURCE_ORDER[seen[modId]]) then
			seen[modId] = source
		end
	end
	local affixes = item.affixes
	-- Essence modifiers of this item class
	local essenceOf = { }
	local itemType = essenceItemType(item)
	for _, essence in pairs(data.essences) do
		local modId = essence.mods and essence.mods[itemType]
		local mod = modId and not modId:match("^EssenceDisplay") and affixes[modId]
		if mod and mod.type == affixType then
			essenceOf[modId] = essenceOf[modId] or essence
			add(modId, mod, "essence", "essence|" .. (essence.type or essenceFamilyName(essence.name)), essence.name, essence.tierLevel)
		end
	end
	-- Regular, rune-influenced and other modifiers of the class's table
	local influenceTags = { }
	local baseType, specificType = item:GetSocketedAugmentTypes()
	for _, tag in ipairs(data.runeInfluences and (data.runeInfluences[specificType] or data.runeInfluences[baseType]) or { }) do
		t_insert(influenceTags, tag)
	end
	for modId, mod in pairs(affixes) do
		if mod.type == affixType then
			local key = statOrderKey(mod)
			if item:GetModSpawnWeight(mod, extraTags) > 0 then
				add(modId, mod, "regular", "regular|" .. key)
			else
				local influence
				for _, tag in ipairs(influenceTags) do
					if hasSpawnTag(mod, tag) and item:GetModSpawnWeight(mod, { [tag] = true }) > 0 then
						influence = tag
						break
					end
				end
				if influence then
					add(modId, mod, "influence", "influence|" .. influence .. "|" .. key)
				elseif not essenceOf[modId] and madeForBase(item, mod) then
					add(modId, mod, "other", "other|" .. key)
				end
			end
			if withAny then
				add(modId, mod, "any", "any|" .. key)
			end
		end
	end
	-- Desecrated modifiers
	for modId, mod in pairs(data.itemMods.Desecrated) do
		if mod.type == affixType and isDesecrated(mod) then
			local key = statOrderKey(mod)
			if item:GetModSpawnWeight(mod, extraTags) > 0 then
				add(modId, mod, "desecrated", "desecrated|" .. key)
			end
			if withAny then
				add(modId, mod, "any", "any|" .. key)
			end
		end
	end
	return out, seen
end

local function candidatesFor(item, affixType, extraTags, withAny)
	if next(extraTags) then
		return buildCandidates(item, affixType, extraTags, withAny)
	end
	local cacheKey = tostring(item.baseName) .. "|" .. affixType .. (withAny and "|any" or "")
	local entry = candidateCache[cacheKey]
	if not entry then
		-- A few bases at a time (the editor's item and the one before)
		if candidateCacheSize >= 8 then
			candidateCache = { }
			candidateCacheSize = 0
		end
		local list, seen = buildCandidates(item, affixType, extraTags, withAny)
		entry = { list = list, seen = seen }
		candidateCache[cacheKey] = entry
		candidateCacheSize = candidateCacheSize + 1
	end
	return entry.list, entry.seen
end

-- Groups and tags of the modifiers in the item's other affix slots (PoB's UpdateAffixControl)
local function otherSlots(item, outputTable, outputIndex)
	local excludeGroups, extraTags = { }, { }
	for _, tableName in ipairs({ "prefixes", "suffixes" }) do
		for index = 1, (item[tableName].limit or (item.affixLimit / 2)) do
			if index ~= outputIndex or tableName ~= outputTable then
				local affix = item[tableName][index]
				local mod = affix and item.affixes[affix.modId]
				if mod then
					if mod.group then
						excludeGroups[mod.group] = true
					end
					for _, tag in ipairs(mod.tags or { }) do
						extraTags[tag] = true
					end
				end
			end
		end
	end
	return excludeGroups, extraTags
end

-- Numbers of a line: its text with "#" for each and the { lo, hi } of each (a range or a number)
local function lineTokens(line)
	local parts, vals, pos = { }, { }, 1
	while true do
		local rs, re, a, b = line:find(RANGE, pos)
		local ns, ne, n = line:find("(%d+%.?%d*)", pos)
		if not rs and not ns then
			break
		end
		if rs and (not ns or rs <= ns) then
			t_insert(parts, line:sub(pos, rs - 1))
			t_insert(vals, { tonumber(a), tonumber(b), m_max(decimals(a), decimals(b)) })
			pos = re + 1
		else
			t_insert(parts, line:sub(pos, ns - 1))
			t_insert(vals, { tonumber(n), tonumber(n), decimals(n) })
			pos = ne + 1
		end
		t_insert(parts, "#")
	end
	t_insert(parts, line:sub(pos))
	return table.concat(parts), vals
end

local function formatNumber(v, places)
	if places and places > 0 then
		return (string.format("%." .. places .. "f", v):gsub("0+$", ""):gsub("%.$", ""))
	end
	return tostring(m_floor(v + 0.5))
end

-- A family's text over all its tiers: "+(10-149) to maximum Life"
local function familyText(tiers)
	local best = tiers[1].mod
	if #tiers == 1 then
		return table.concat(best, " / ")
	end
	local lines = { }
	for l, line in ipairs(best) do
		local skeleton, vals = lineTokens(line)
		local merged = { }
		for k, v in ipairs(vals) do
			merged[k] = { v[1], v[2], v[3] }
		end
		local compatible = true
		for t = 2, #tiers do
			local other = tiers[t].mod[l]
			if not other then
				compatible = false
				break
			end
			local s2, v2 = lineTokens(other)
			if s2 ~= skeleton or #v2 ~= #vals then
				compatible = false
				break
			end
			for k, v in ipairs(v2) do
				merged[k][1] = m_min(merged[k][1], v[1], v[2])
				merged[k][2] = m_max(merged[k][2], v[1], v[2])
				merged[k][3] = m_max(merged[k][3], v[3])
			end
		end
		if compatible then
			local k = 0
			lines[l] = skeleton:gsub("#", function()
				k = k + 1
				local v = merged[k]
				if v[1] == v[2] then
					return formatNumber(v[1], v[3])
				end
				return "(" .. formatNumber(v[1], v[3]) .. "-" .. formatNumber(v[2], v[3]) .. ")"
			end)
		else
			lines[l] = line
		end
	end
	return table.concat(lines, " / ")
end

local SOURCE_LABELS = {
	regular = "Regular",
	essence = "Essence",
	desecrated = "Desecrated",
	influence = "Rune-influenced",
	other = "Other",
	any = "Any modifier",
}

-- Families of candidates: { key, source, label, text, tags, tiers = { { modId, mod, tierName } } best first }
local function buildFamilies(item, list, excludeGroups, sources)
	local byKey, order = { }, { }
	for _, c in ipairs(list) do
		if (not sources or sources[c.source]) and not (c.mod.group and excludeGroups[c.mod.group]) then
			local fam = byKey[c.family]
			if not fam then
				fam = { key = c.family, source = c.source, tiers = { }, ids = { } }
				byKey[c.family] = fam
				t_insert(order, fam)
			end
			-- (Several essences of a kind can give the same modifier: the highest one is listed)
			if not fam.ids[c.modId] or c.sortLevel > fam.ids[c.modId].sortLevel then
				if fam.ids[c.modId] then
					for i, other in ipairs(fam.tiers) do
						if other == fam.ids[c.modId] then
							table.remove(fam.tiers, i)
							break
						end
					end
				end
				fam.ids[c.modId] = c
				t_insert(fam.tiers, c)
			end
		end
	end
	for _, fam in ipairs(order) do
		t_sort(fam.tiers, function(a, b)
			if a.sortLevel ~= b.sortLevel then
				return a.sortLevel > b.sortLevel
			end
			return a.modId < b.modId
		end)
		local best = fam.tiers[1]
		fam.text = familyText(fam.tiers)
		if fam.source == "essence" then
			fam.label = essenceFamilyName(best.tierName or "Essence")
		else
			fam.label = fam.text
		end
		fam.statOrder = best.mod.statOrder and best.mod.statOrder[1] or 0
		fam.tags = best.mod.modTags or { }
		fam.influence = fam.source == "influence" and fam.key:match("^influence|([^|]+)|") or nil
	end
	t_sort(order, function(a, b)
		if a.source ~= b.source then
			return SOURCE_ORDER[a.source] < SOURCE_ORDER[b.source]
		end
		if a.statOrder ~= b.statOrder then
			return a.statOrder < b.statOrder
		end
		return a.key < b.key
	end)
	return order, byKey
end

local function tierJson(fam, index, c, itemLevel)
	local mod = c.mod
	local tier = {
		modId = c.modId,
		tier = index,
		level = mod.level or 0,
		name = c.tierName or mod.affix or "",
		lines = api.array(),
		available = not itemLevel or (mod.level or 0) <= itemLevel,
	}
	for i, line in ipairs(mod) do
		tier.lines[i] = line
	end
	return tier
end

local function familyJson(fam, itemLevel)
	local out = {
		key = fam.key,
		source = fam.source,
		sourceLabel = SOURCE_LABELS[fam.source],
		label = fam.label,
		text = fam.text,
		influence = fam.influence,
		tags = api.array(),
		tiers = api.array(),
	}
	for i, tag in ipairs(fam.tags) do
		out.tags[i] = tag
	end
	for i, c in ipairs(fam.tiers) do
		out.tiers[i] = tierJson(fam, i, c, itemLevel)
	end
	return out
end

local function slotList(item, slot)
	if slot == "suffix" then
		return item.suffixes, "suffixes", "Suffix"
	end
	return item.prefixes, "prefixes", "Prefix"
end

local function slotLimit(item, list)
	return list.limit or (item.affixLimit / 2)
end

---------------------------------------------------------------------------------------------------
-- The editor's model
---------------------------------------------------------------------------------------------------

local function slotJson(item, slot, index)
	local list, tableName, affixType = slotList(item, slot)
	local affix = list[index] or { modId = "None" }
	local out = { index = index, type = affixType }
	local mod = affix.modId ~= "None" and item.affixes[affix.modId]
	if not mod then
		return out
	end
	out.modId = affix.modId
	out.fractured = affix.fractured and true or false
	out.affix = mod.affix
	out.level = mod.level or 0
	-- The family it belongs to (from its main source), with its tiers
	local excludeGroups, extraTags = otherSlots(item, tableName, index)
	local function familyOf(list)
		local found
		for _, c in ipairs(list) do
			if c.modId == affix.modId and (not found or SOURCE_ORDER[c.source] < SOURCE_ORDER[found.source]) then
				found = c
			end
		end
		if not found then
			return nil
		end
		local members = { }
		for _, c in ipairs(list) do
			if c.family == found.family then
				t_insert(members, c)
			end
		end
		return buildFamilies(item, members, excludeGroups)[1]
	end
	local family = familyOf(candidatesFor(item, affixType, extraTags, false))
		-- A modifier outside the item's pools ("any")
		or familyOf(candidatesFor(item, affixType, extraTags, true))
	if family then
		local fj = familyJson(family, item.itemLevel)
		out.family = fj.key
		out.source = fj.source
		out.sourceLabel = fj.sourceLabel
		out.label = fj.label
		out.text = fj.text
		out.tiers = fj.tiers
		for i, c in ipairs(family.tiers) do
			if c.modId == affix.modId then
				out.tier = i
				out.name = c.tierName or mod.affix
			end
		end
	else
		out.source = "any"
		out.label = table.concat(mod, " / ")
	end
	-- Lines and values
	out.lines = api.array()
	for i, line in ipairs(modLines(mod, affix.range)) do
		out.lines[i] = line
	end
	out.values = api.array()
	local rolls = valueRolls(mod, affix.range)
	local k = 0
	for l, line in ipairs(mod) do
		for _, r in ipairs(lineRanges(line)) do
			k = k + 1
			local roll = rolls[k] or 0.5
			t_insert(out.values, {
				line = l,
				min = r.min,
				max = r.max,
				decimals = r.decimals,
				roll = roll,
				value = roundTo(r.min + roll * (r.max - r.min), r.decimals),
			})
		end
	end
	return out
end

-- The explicit lines outside the crafted affixes (custom, essence or desecrated lines PoB added)
local function extraLines(item)
	local out = api.array()
	for index, modLine in ipairs(item.explicitModLines) do
		if not item.crafted or modLine.custom then
			local text = itemLib.formatModLine(modLine)
			if text then
				t_insert(out, { index = index, text = text, custom = modLine.custom and true or false })
			end
		end
	end
	return out
end

-- PoB's range lines (implicits, uniques' modifiers, custom lines with ranges)
local function rangeLines(item)
	local out = api.array()
	local kinds = { }
	for kind, lines in pairs({ implicit = item.implicitModLines, enchant = item.enchantModLines, rune = item.runeModLines }) do
		for _, modLine in ipairs(lines or { }) do
			kinds[modLine] = kind
		end
	end
	for index, modLine in ipairs(item.rangeLineList or { }) do
		local ranges = lineRanges(modLine.line)
		local text = itemLib.formatModLine(modLine) or modLine.line
		local values = api.array()
		for i, r in ipairs(ranges) do
			values[i] = { min = r.min, max = r.max, decimals = r.decimals }
		end
		t_insert(out, {
			index = index,
			line = modLine.line,
			text = text,
			roll = modLine.range or main.defaultItemAffixQuality or 0.5,
			values = values,
			kind = kinds[modLine] or "explicit",
		})
	end
	return out
end

local function canCraft(item)
	return item.affixes ~= nil and (item.rarity == "MAGIC" or item.rarity == "RARE")
end

function api.craftModel(item)
	patch()
	local out = {
		rarity = item.rarity,
		title = item.title,
		baseName = item.baseName,
		type = item.type,
		itemLevel = item.itemLevel,
		crafted = item.crafted and true or false,
		unique = item.rarity == "UNIQUE" or item.rarity == "RELIC",
		canSetRarity = item.affixes ~= nil and not (item.rarity == "UNIQUE" or item.rarity == "RELIC")
			and item.base.type ~= "Transcendent Limb",
		magicOnly = item.base.flask ~= nil or item.base.type == "Charm" or (item.base.type == "Jewel" and item.base.subType == "Charm"),
		requiredLevel = item.requirements and item.requirements.level or 0,
		prefixes = api.array(),
		suffixes = api.array(),
		extra = extraLines(item),
		ranges = rangeLines(item),
	}
	if item.crafted then
		out.prefixLimit = slotLimit(item, item.prefixes)
		out.suffixLimit = slotLimit(item, item.suffixes)
		for i = 1, out.prefixLimit do
			out.prefixes[i] = slotJson(item, "prefix", i)
		end
		for i = 1, out.suffixLimit do
			out.suffixes[i] = slotJson(item, "suffix", i)
		end
	else
		out.convertible = canCraft(item)
	end
	return out
end

---------------------------------------------------------------------------------------------------
-- Pools and previews
---------------------------------------------------------------------------------------------------

local function slotArgs(args, item)
	local list, tableName, affixType = slotList(item, args.slot)
	local index = tonumber(args.index) or 1
	if not item.crafted then
		error("this item has no crafted modifiers", 0)
	end
	if index < 1 or index > slotLimit(item, list) then
		error("no " .. affixType:lower() .. " slot " .. tostring(args.index), 0)
	end
	return list, tableName, affixType, index
end

-- Families for a prefix / suffix slot. args: { slot = "prefix" | "suffix", index, any = bool }
-- Returns { families, sources = { { id, label, count } } }
function api.craftPool(args)
	patch()
	local item = displayItem()
	local _, tableName, affixType, index = slotArgs(args, item)
	local excludeGroups, extraTags = otherSlots(item, tableName, index)
	local list = candidatesFor(item, affixType, extraTags, args.any and true or false)
	local families = buildFamilies(item, list, excludeGroups)
	local out = { families = api.array(), itemLevel = item.itemLevel }
	for i, fam in ipairs(families) do
		out.families[i] = familyJson(fam, item.itemLevel)
	end
	return out
end

-- A copy of the display item with one slot changed
local function withSlot(item, slot, index, modId, range, fractured)
	local testItem = new("Item"):Item(item:BuildRaw())
	testItem.id = item.id
	local list = slot == "suffix" and testItem.suffixes or testItem.prefixes
	list[index] = { modId = modId or "None", range = range, fractured = fractured }
	testItem:Craft()
	return testItem
end

local function normaliseRange(args)
	if type(args.ranges) == "table" and #args.ranges > 0 then
		local same = true
		for i = 2, #args.ranges do
			if args.ranges[i] ~= args.ranges[1] then
				same = false
			end
		end
		local ranges = { }
		for i, r in ipairs(args.ranges) do
			ranges[i] = m_max(0, m_min(1, tonumber(r) or 0.5))
		end
		if same or #ranges == 1 then
			return ranges[1]
		end
		return ranges
	end
	local r = tonumber(args.range)
	return r and m_max(0, m_min(1, r)) or (main.defaultItemAffixQuality or 0.5)
end

-- PoB's stat changes of putting a modifier into a slot (or emptying it).
-- args: { slot, index, modId, range | ranges } -> lines
function api.craftPreview(args)
	patch()
	local tab = itemsTab()
	local item = displayItem()
	local list, _, _, index = slotArgs(args, item)
	local modId = args.modId or "None"
	if modId ~= "None" and not item.affixes[modId] then
		error("unknown modifier " .. tostring(modId), 0)
	end
	local testItem = withSlot(item, args.slot, index, modId, normaliseRange(args), list[index] and list[index].fractured)
	local slotName = tab:GetComparisonSlotNameForItem(item)
	local calcFunc = build.calcsTab:GetMiscCalculator()
	local outputBase = calcFunc({ repSlotName = slotName, repItem = item })
	local outputNew = calcFunc({ repSlotName = slotName, repItem = testItem })
	local tt = api.newTooltipRecorder()
	local current = list[index] and list[index].modId ~= "None"
	local header = modId == "None" and "^7Removing this modifier will give you:" or current and "^7Changing to this modifier will give you:" or "^7Adding this modifier will give you:"
	local count = build:AddStatComparesToTooltip(tt, outputBase, outputNew, header)
	local lines = api.array()
	if count == 0 then
		lines[1] = "^7No change to the displayed statistics."
		return lines
	end
	for _, line in ipairs(tt.lines) do
		if not line.separator and line.text and line.text ~= "" then
			t_insert(lines, line.text)
		end
	end
	return lines
end

-- Statistics the pool can be sorted by (PoB's modifier sorting list)
function api.craftSortStats()
	local out = api.array()
	for _, entry in ipairs(data.powerStatList) do
		if entry.stat and not entry.ignoreForItems then
			t_insert(out, { label = entry.label, stat = entry.stat })
		end
	end
	return out
end

-- Change of a statistic for putting each modifier into a slot (at its roll "range").
-- args: { slot, index, stat, modIds = [...], range } -> { [modId] = value }
function api.craftPoolValues(args)
	patch()
	local tab = itemsTab()
	local item = displayItem()
	local list, _, _, index = slotArgs(args, item)
	local sortOption
	for _, entry in ipairs(data.powerStatList) do
		if entry.stat == args.stat and not entry.ignoreForItems then
			sortOption = entry
			break
		end
	end
	if not sortOption then
		error("unknown statistic " .. tostring(args.stat), 0)
	end
	local slotName = tab:GetComparisonSlotNameForItem(item)
	local calcFunc = build.calcsTab:GetMiscCalculator()
	local useFullDPS = sortOption.stat == "FullDPS"
	local baseItem = withSlot(item, args.slot, index, "None")
	local baseValue = data.powerStatList.GetFromOutput(calcFunc({ repSlotName = slotName, repItem = baseItem }, useFullDPS), sortOption)
	local range = tonumber(args.range) or (main.defaultItemAffixQuality or 0.5)
	local out = api.object()
	for _, modId in ipairs(args.modIds or { }) do
		if item.affixes[modId] then
			local testItem = withSlot(item, args.slot, index, modId, range)
			local value = data.powerStatList.GetFromOutput(calcFunc({ repSlotName = slotName, repItem = testItem }, useFullDPS), sortOption)
			out[modId] = (value or 0) - (baseValue or 0)
		end
	end
	return out
end

---------------------------------------------------------------------------------------------------
-- Changes
---------------------------------------------------------------------------------------------------

local function refresh(item)
	local tab = itemsTab()
	local id = item.id
	tab:SetDisplayItem(item)
	item.id = id
	return api.craftState()
end

-- Sets a prefix / suffix. args: { slot, index, modId (nil / "None" empties it), range | ranges, fractured }
function api.craftSetAffix(args)
	patch()
	local item = displayItem()
	local list, _, _, index = slotArgs(args, item)
	local modId = args.modId or "None"
	if modId ~= "None" and not item.affixes[modId] then
		error("unknown modifier " .. tostring(modId), 0)
	end
	if modId == "None" then
		list[index] = { modId = "None" }
	else
		list[index] = { modId = modId, range = normaliseRange(args), fractured = args.fractured and true or nil }
	end
	item:Craft()
	return refresh(item)
end

-- Trims the affix lists to the item's limits
local function trimAffixes(item)
	for _, list in ipairs({ item.prefixes, item.suffixes }) do
		local limit = slotLimit(item, list)
		for i = #list, limit + 1, -1 do
			list[i] = nil
		end
	end
end

-- Item properties. args: { rarity = "NORMAL" | "MAGIC" | "RARE", title, itemLevel (0 / nil clears) }
function api.craftSetItem(args)
	patch()
	local item = displayItem()
	if args.rarity and args.rarity ~= item.rarity then
		if item.rarity == "UNIQUE" or item.rarity == "RELIC" or not item.affixes then
			error("the rarity of this item cannot be changed", 0)
		end
		local rarity = args.rarity
		if rarity ~= "NORMAL" and rarity ~= "MAGIC" and rarity ~= "RARE" then
			error("unknown rarity " .. tostring(rarity), 0)
		end
		if rarity == "RARE" and (item.base.flask or item.base.type == "Charm") then
			error("flasks and charms can only be magic", 0)
		end
		-- An item whose lines are not crafted keeps them (they only become affixes by converting it)
		local keepLines = not item.crafted and #item.explicitModLines > 0
		item.rarity = rarity
		if rarity == "RARE" then
			item.title = item.title or "New Item"
		else
			item.title = nil
		end
		if rarity == "NORMAL" then
			item.crafted = false
			wipeTable(item.prefixes)
			wipeTable(item.suffixes)
			if not keepLines then
				local kept = { }
				for _, modLine in ipairs(item.explicitModLines) do
					if modLine.custom then
						t_insert(kept, modLine)
					end
				end
				item.explicitModLines = kept
			end
			item:BuildAndParseRaw()
		elseif keepLines then
			item:BuildAndParseRaw()
		else
			item.crafted = true
			item:BuildAndParseRaw()
			trimAffixes(item)
			item:Craft()
		end
	end
	if args.title ~= nil and item.rarity == "RARE" then
		local title = tostring(args.title):gsub("^%s+", ""):gsub("%s+$", "")
		item.title = title ~= "" and title or "New Item"
		item:BuildAndParseRaw()
	end
	if args.itemLevel ~= nil then
		local level = tonumber(args.itemLevel)
		item.itemLevel = (level and level > 0) and m_min(m_floor(level), 100) or nil
		item:BuildAndParseRaw()
	end
	return refresh(item)
end

-- Roll of one of PoB's range lines. args: { index, range }
function api.craftSetRange(args)
	local tab = itemsTab()
	local item = displayItem()
	local modLine = item.rangeLineList[tonumber(args.index) or 0]
	if not modLine then
		error("unknown range line " .. tostring(args.index), 0)
	end
	modLine.range = m_max(0, m_min(1, tonumber(args.range) or 0.5))
	item:BuildAndParseRaw()
	tab:UpdateDisplayItemTooltip()
	tab:UpdateCustomControls()
	tab:UpdateDisplayItemRangeLines()
	return api.craftState()
end

-- Removes an explicit line that is not a crafted affix (as PoB's "Remove" of custom modifiers).
-- args: { index }
function api.craftRemoveLine(args)
	local item = displayItem()
	local index = tonumber(args.index)
	if not item.explicitModLines[index] then
		error("unknown modifier line " .. tostring(args.index), 0)
	end
	table.remove(item.explicitModLines, index)
	item:BuildAndParseRaw()
	local id = item.id
	itemsTab():CreateDisplayItemFromRaw(item:BuildRaw())
	itemsTab().displayItem.id = id
	return api.craftState()
end

-- Adds a line of text as a custom modifier. args: { text }
function api.craftAddLine(args)
	local item = displayItem()
	local text = tostring(args.text or ""):gsub("\r", "")
	local newItem = new("Item"):Item(item:BuildRaw())
	newItem.id = item.id
	for line in text:gmatch("[^\n]+") do
		if line:match("%S") then
			t_insert(newItem.explicitModLines, { line = line, custom = true })
		end
	end
	newItem:BuildAndParseRaw()
	return refresh(newItem)
end

---------------------------------------------------------------------------------------------------
-- Converting an item's lines into crafted affixes
---------------------------------------------------------------------------------------------------

-- "+87 to maximum Life" / "+(80-89) to maximum Life" -> "+# to maximum Life"
local function skeletonOf(line)
	local s = lineTokens(line)
	return s:lower()
end

local function valuesOf(line)
	local _, vals = lineTokens(line)
	return vals
end

-- The rolls that give a line's values with a modifier line's ranges, or nil if they do not fit
local function fitLine(modLine, line)
	local ms, mvals = lineTokens(modLine)
	local ls, lvals = lineTokens(line)
	if ms:lower() ~= ls:lower() or #mvals ~= #lvals then
		return nil
	end
	local rolls = { }
	for k, mv in ipairs(mvals) do
		local v = lvals[k][1]
		local lo, hi = m_min(mv[1], mv[2]), m_max(mv[1], mv[2])
		local tolerance = 10 ^ -(mv[3] + 1)
		if v < lo - tolerance or v > hi + tolerance then
			return nil
		end
		if mv[1] ~= mv[2] then
			t_insert(rolls, m_max(0, m_min(1, (v - mv[1]) / (mv[2] - mv[1]))))
		end
	end
	return rolls
end

-- Turns a magic / rare item's explicit lines into crafted prefixes and suffixes where they match
-- a modifier of the item (lines that do not match stay as custom lines).
function api.craftConvert()
	patch()
	local item = displayItem()
	if item.crafted or not canCraft(item) then
		error("this item cannot be converted", 0)
	end
	-- The lines to match: explicit lines without ranges (or with their ranges applied)
	local lines = { }
	for index, modLine in ipairs(item.explicitModLines) do
		local text = itemLib.formatModLine(modLine) and StripEscapes(itemLib.formatModLine(modLine)) or modLine.line
		t_insert(lines, { index = index, modLine = modLine, text = text, skeleton = skeletonOf(text), used = false })
	end
	-- Candidate modifiers: everything the item class can have
	local candidates = { }
	for _, affixType in ipairs({ "Prefix", "Suffix" }) do
		local list = candidatesFor(item, affixType, { }, true)
		for _, c in ipairs(list) do
			if c.source == "any" then
				t_insert(candidates, c)
			end
		end
	end
	local rarityOrder = { regular = 1, desecrated = 2, essence = 3, influence = 4, other = 5, any = 6 }
	local _, seenPrefix = candidatesFor(item, "Prefix", { }, false)
	local _, seenSuffix = candidatesFor(item, "Suffix", { }, false)
	local function preference(c)
		return rarityOrder[(c.mod.type == "Prefix" and seenPrefix or seenSuffix)[c.modId] or "any"] or 6
	end
	-- Matches: modifiers whose every line is one of the item's lines, with values in range
	local matches = { }
	local bySkeleton = { }
	for _, line in ipairs(lines) do
		bySkeleton[line.skeleton] = bySkeleton[line.skeleton] or { }
		t_insert(bySkeleton[line.skeleton], line)
	end
	for _, c in ipairs(candidates) do
		local mod = c.mod
		local used, rolls, ok = { }, { }, #mod > 0
		for _, modLine in ipairs(mod) do
			local found
			for _, line in ipairs(bySkeleton[skeletonOf(modLine)] or { }) do
				if not used[line] then
					local r = fitLine(modLine, line.text)
					if r then
						found = line
						for _, x in ipairs(r) do
							t_insert(rolls, x)
						end
						break
					end
				end
			end
			if not found then
				ok = false
				break
			end
			used[found] = true
		end
		if ok then
			local covered = { }
			for line in pairs(used) do
				t_insert(covered, line)
			end
			t_insert(matches, { c = c, lines = covered, rolls = rolls, pref = preference(c) })
		end
	end
	-- Hybrid modifiers first (more lines explained), then the usual sources, then higher tiers
	t_sort(matches, function(a, b)
		if #a.lines ~= #b.lines then
			return #a.lines > #b.lines
		end
		if a.pref ~= b.pref then
			return a.pref < b.pref
		end
		if (a.c.mod.level or 0) ~= (b.c.mod.level or 0) then
			return (a.c.mod.level or 0) > (b.c.mod.level or 0)
		end
		return a.c.modId < b.c.modId
	end)
	-- A hybrid modifier only takes lines another modifier cannot explain alone
	local single = { }
	for _, m in ipairs(matches) do
		if #m.lines == 1 then
			single[m.lines[1]] = true
		end
	end
	item.crafted = true
	item:BuildAndParseRaw()
	local prefixLimit = slotLimit(item, item.prefixes)
	local suffixLimit = slotLimit(item, item.suffixes)
	for _, list in ipairs({ item.prefixes, item.suffixes }) do
		for i = #list, 1, -1 do
			list[i] = nil
		end
	end
	local groups = { }
	for _, m in ipairs(matches) do
		local free = true
		for _, line in ipairs(m.lines) do
			if line.used then
				free = false
			end
		end
		if free and #m.lines > 1 then
			local allSingle = true
			for _, line in ipairs(m.lines) do
				if not single[line] then
					allSingle = false
				end
			end
			-- Every line has a modifier of its own: prefer those
			if allSingle then
				free = false
			end
		end
		local mod = m.c.mod
		local list = mod.type == "Prefix" and item.prefixes or item.suffixes
		local limit = mod.type == "Prefix" and prefixLimit or suffixLimit
		if free and #list < limit and not (mod.group and groups[mod.group]) then
			for _, line in ipairs(m.lines) do
				line.used = true
			end
			if mod.group then
				groups[mod.group] = true
			end
			local range
			if #m.rolls == 0 then
				range = main.defaultItemAffixQuality or 0.5
			elseif #m.rolls == 1 then
				range = m.rolls[1]
			else
				range = m.rolls
				local same = true
				for i = 2, #m.rolls do
					if math.abs(m.rolls[i] - m.rolls[1]) > 1e-9 then
						same = false
					end
				end
				if same then
					range = m.rolls[1]
				end
			end
			local fractured
			for _, line in ipairs(m.lines) do
				if line.modLine.fractured then
					fractured = true
				end
			end
			t_insert(list, { modId = m.c.modId, range = range, fractured = fractured })
		end
	end
	-- Lines that are not affixes stay as custom lines
	local kept = { }
	for _, line in ipairs(lines) do
		if not line.used then
			line.modLine.custom = true
			t_insert(kept, line.modLine)
		end
	end
	item.explicitModLines = kept
	for i = #item.prefixes + 1, prefixLimit do
		item.prefixes[i] = { modId = "None" }
	end
	for i = #item.suffixes + 1, suffixLimit do
		item.suffixes[i] = { modId = "None" }
	end
	item:Craft()
	return refresh(item)
end

-- Before any build is loaded: items saved with desecrated affixes keep them
patch()