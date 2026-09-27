-- Character import: the Character Import section of PoB's Import/Export tab (ImportTab.lua), on
-- the character the app downloaded from the Path of Exile API (GET /character/poe2/<name>, the
-- download PoB's ImportTab:DownloadCharacter makes). PoB's own functions do the import.

-- Imports a character into the open build.
-- args: { character = the API response's "character" object,
--         tree, items (import the passive tree and jewels / the items and skills),
--         clearJewels, clearSkills, clearItems, ignoreWeaponSwap (PoB's checkboxes) }
-- Returns { xml = the build's PoB XML, state }
function api.importCharacter(args)
	local charData = args.character
	if type(charData) ~= "table" or type(charData.passives) ~= "table" or type(charData.equipment) ~= "table" then
		error("The character data is incomplete (no passives or equipment)", 0)
	end
	local importTab = build.importTab
	local controls = importTab.controls
	controls.charImportTreeClearJewels.state = args.clearJewels ~= false
	controls.charImportItemsClearSkills.state = args.clearSkills ~= false
	controls.charImportItemsClearItems.state = args.clearItems ~= false
	controls.charImportItemsIgnoreWeaponSwap.state = args.ignoreWeaponSwap == true
	-- Lists PoB iterates over (absent when the character has none)
	charData.jewels = charData.jewels or { }
	charData.skills = charData.skills or { }
	local passives = charData.passives
	passives.hashes = passives.hashes or { }
	passives.specialisations = passives.specialisations or { }
	passives.skill_overrides = passives.skill_overrides or { }
	passives.quest_stats = passives.quest_stats or { }

	-- Like PoB's import of a character JSON (the Import button with JSON data)
	if args.tree ~= false then
		importTab:ImportPassiveTreeAndJewels(charData)
		build.calcsTab:BuildOutput()
	end
	if args.items ~= false then
		importTab:ImportItemsAndSkills(charData)
	end
	-- Jewel sockets follow the imported tree; the calculation and the slots, as after item changes
	api.refreshItems()
	return { xml = api.saveXml(), state = api.state() }
end
