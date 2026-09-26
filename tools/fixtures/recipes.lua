-- Test characters for the calculation engine, built with Path of Building's own functions by
-- tools/pob_reference.py generate. Each recipe runs on a new empty build.
-- The trees are allocated deterministically (nearest matching notables first), so they are
-- plausible rather than optimal; what matters is covering PoB's calculation paths.

local function selectClass(className, ascendancyName)
	local spec = build.spec
	local classId = spec.tree.classNameMap[className]
	assert(classId, "class " .. className)
	spec:SelectClass(classId)
	if ascendancyName then
		for ascId, asc in pairs(spec.tree.classes[classId].classes) do
			if asc.name == ascendancyName then
				spec:SelectAscendClass(ascId)
				return
			end
		end
		error("ascendancy " .. ascendancyName)
	end
end

local function nodeText(node)
	return table.concat(node.sd or { }, " ")
end

-- Allocates the nearest notables whose text matches one of the keywords, then the nearest
-- notables of any kind, until about `points` points are spent.
local function allocNotables(points, keywords)
	local spec = build.spec
	local function pick(filter)
		local best
		for _, node in pairs(spec.nodes) do
			if not node.alloc and node.type == "Notable" and not node.ascendancyName and node.path and #node.path > 0
				and (not filter or filter(node)) then
				if not best or node.pathDist < best.pathDist or (node.pathDist == best.pathDist and node.id < best.id) then
					best = node
				end
			end
		end
		return best
	end
	local function matches(node)
		local text = nodeText(node):lower()
		for _, k in ipairs(keywords or { }) do
			if text:find(k:lower(), 1, true) then
				return true
			end
		end
		return false
	end
	local used = 0
	while used < points do
		local node = (keywords and pick(function(n) return matches(n) and n.pathDist <= 6 end)) or pick()
		if not node then
			break
		end
		used = used + node.pathDist
		spec:AllocNode(node)
	end
end

-- Allocates the ascendancy's notables, nearest first, up to 8 points
local function allocAscendancy()
	local spec = build.spec
	local ascName = spec.curAscendClassName
	local used = 0
	while true do
		local best
		for _, node in pairs(spec.nodes) do
			if not node.alloc and node.ascendancyName == ascName and node.path and #node.path > 0
				and not node.isMultipleChoiceOption and node.type ~= "AscendClassStart" then
				if not best or node.pathDist < best.pathDist or (node.pathDist == best.pathDist and node.id < best.id) then
					best = node
				end
			end
		end
		if not best or used + best.pathDist > 8 then
			break
		end
		used = used + best.pathDist
		spec:AllocNode(best)
	end
end

local function unique(name)
	for _, list in pairs(data.uniques) do
		for _, raw in ipairs(list) do
			if raw:match("^%s*([^\n]+)") == name then
				return raw
			end
		end
	end
	error("unique " .. name)
end

local function addItem(raw)
	build.itemsTab:CreateDisplayItemFromRaw(raw)
	assert(build.itemsTab.displayItem and build.itemsTab.displayItem.base, "item did not parse:\n" .. raw)
	build.itemsTab:AddDisplayItem()
end

local function rare(base, name, ...)
	local lines = { "Rarity: RARE", name, base, "Quality: 20" }
	for _, line in ipairs({ ... }) do
		lines[#lines + 1] = line
	end
	addItem(table.concat(lines, "\n"))
end

local function addSkills(text, includeInFullDPS)
	build.skillsTab:PasteSocketGroup(text)
	local list = build.skillsTab.socketGroupList
	if includeInFullDPS then
		list[#list].includeInFullDPS = true
	end
end

local function setConfig(var, value)
	build.configTab.input[var] = value
	build.configTab:UpdateControls()
	build.configTab:BuildModList()
end

local function defenceGear(kind)
	if kind == "es" then
		rare("Silk Robe", "Storm Shroud", "+120 to maximum Energy Shield", "80% increased Energy Shield", "+40% to Lightning Resistance", "+30% to Cold Resistance")
	else
		rare("Iron Cuirass", "Oak Shell", "+90 to maximum Life", "60% increased Armour", "+40% to Fire Resistance", "+30% to Cold Resistance")
	end
	rare("Iron Greaves", "Dust Stride", "30% increased Movement Speed", "+60 to maximum Life", "+35% to Lightning Resistance")
	rare("Riveted Mitts", "Grip Clutch", "+55 to maximum Life", "+30% to Fire Resistance", "+20 to Strength")
	rare("Soldier Greathelm", "Brow Guard", "+65 to maximum Life", "+30% to Cold Resistance", "+15% to Chaos Resistance")
	rare("Ruby Ring", "Blood Loop", "+40 to maximum Life", "+30% to Lightning Resistance", "+25% to Fire Resistance")
	rare("Iron Ring", "Storm Loop", "+45 to maximum Life", "+30% to Cold Resistance", "+20 to Dexterity")
	rare("Jade Amulet", "Grim Beads", "+50 to maximum Life", "+30 to Dexterity", "+25 to Intelligence")
	rare("Wide Belt", "Vice Coil", "+70 to maximum Life", "+30% to Fire Resistance", "+20% to Cold Resistance")
end

local recipes = { }

-- Bow attack with a persistent buff (spirit) and elemental conversion
function recipes.deadeye_lightning_arrow()
	selectClass("Ranger", "Deadeye")
	build.spec.attributeIndex = 2
	allocNotables(70, { "projectile", "lightning", "bow" })
	allocAscendancy()
	build.characterLevel = 90
	build.characterLevelAutoMode = false
	rare("Recurve Bow", "Storm Fletch", "Adds 25 to 48 Physical Damage", "Adds 4 to 96 Lightning Damage", "18% increased Attack Speed", "+3 to Level of all Projectile Skills")
	rare("Broadhead Quiver", "Spark Fletch", "+60 to maximum Life", "Adds 3 to 40 Lightning damage to Attacks", "30% increased Projectile Speed")
	defenceGear("life")
	addSkills("Lightning Arrow 20/20  1\nLightning Penetration 1/0  1\nChain I 1/0  1\nHeightened Accuracy I 1/0  1")
	addSkills("Herald of Thunder 20/0  1")
	build.mainSocketGroup = 1
	setConfig("conditionEnemyShocked", true)
end

-- Spell caster on energy shield with a staff
function recipes.stormweaver_spark()
	selectClass("Sorceress", "Stormweaver")
	build.spec.attributeIndex = 3
	allocNotables(70, { "spell", "lightning", "energy shield" })
	allocAscendancy()
	build.characterLevel = 88
	build.characterLevelAutoMode = false
	rare("Voltaic Staff", "Tempest Branch", "+3 to Level of all Spell Skills", "80% increased Spell Damage", "Gain 20% of Damage as Extra Lightning Damage", "25% increased Cast Speed")
	defenceGear("es")
	addSkills("Spark 20/20  1\nLightning Penetration 1/0  1\nSpell Echo 1/0  1\nRapid Casting I 1/0  1")
	addSkills("Arc 20/0  1\nElemental Focus 1/0  1")
	build.mainSocketGroup = 1
end

-- Minions: player stats plus a minion block
function recipes.infernalist_minions()
	selectClass("Witch", "Infernalist")
	build.spec.attributeIndex = 3
	allocNotables(60, { "minion" })
	allocAscendancy()
	build.characterLevel = 85
	build.characterLevelAutoMode = false
	rare("Bone Wand", "Grave Needle", "+2 to Level of all Minion Skills", "Minions deal 40% increased Damage", "+30 to Intelligence")
	defenceGear("es")
	addSkills("Skeletal Warrior 20/0  1\nMinion Pact I 1/0  1")
	addSkills("Raging Spirits 20/0  1")
	build.mainSocketGroup = 1
end

-- Damage over time (poison) with a bow
function recipes.pathfinder_poison()
	selectClass("Ranger", "Pathfinder")
	build.spec.attributeIndex = 2
	allocNotables(70, { "poison", "chaos", "damage over time" })
	allocAscendancy()
	build.characterLevel = 90
	build.characterLevelAutoMode = false
	rare("Recurve Bow", "Venom Twine", "Adds 30 to 55 Physical Damage", "Adds 10 to 22 Chaos Damage", "15% increased Attack Speed", "30% chance to Poison on Hit")
	rare("Broadhead Quiver", "Blight Fletch", "+50 to maximum Life", "20% increased Poison Duration")
	defenceGear("life")
	addSkills("Poisonburst Arrow 20/20  1\nPoison I 1/0  1")
	build.mainSocketGroup = 1
	setConfig("enemyIsBoss", "Pinnacle")
end

-- Melee with weapon-set passives and a second weapon set
function recipes.warrior_boneshatter_weaponsets()
	selectClass("Warrior", "Titan")
	build.spec.attributeIndex = 1
	allocNotables(55, { "mace", "melee", "armour" })
	build.spec.allocMode = 1
	allocNotables(8, { "attack speed", "mace" })
	build.spec.allocMode = 2
	allocNotables(8, { "life", "armour" })
	build.spec.allocMode = 0
	allocAscendancy()
	build.characterLevel = 92
	build.characterLevelAutoMode = false
	rare("Spiked Club", "Brute Knell", "Adds 30 to 52 Physical Damage", "150% increased Physical Damage", "12% increased Attack Speed")
	rare("Splintered Tower Shield", "Oak Barrier", "+80 to maximum Life", "+30% to Fire Resistance")
	defenceGear("life")
	addSkills("Boneshatter 20/20  1\nBrutality I 1/0  1\nRage I 1/0  1")
	build.mainSocketGroup = 1
end

-- Two damaging skills in Full DPS, with a unique weapon
function recipes.monk_invoker_fulldps()
	selectClass("Monk", "Invoker")
	build.spec.attributeIndex = 2
	allocNotables(70, { "quarterstaff", "critical", "cold" })
	allocAscendancy()
	build.characterLevel = 90
	build.characterLevelAutoMode = false
	rare("Wrapped Quarterstaff", "Frost Pole", "Adds 20 to 40 Physical Damage", "Adds 18 to 30 Cold Damage", "30% increased Critical Hit Chance", "12% increased Attack Speed")
	defenceGear("life")
	addSkills("Tempest Flurry 20/20  1\nInexorable Critical I 1/0  1", true)
	addSkills("Ice Strike 20/0  1\nCold Penetration 1/0  1", true)
	build.mainSocketGroup = 1
end

return recipes
