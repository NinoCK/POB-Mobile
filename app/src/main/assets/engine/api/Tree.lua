-- Passive tree: the app owns the allocation (its own tree view and pathing) and pushes it to
-- PoB's spec; node comparisons like PoB's tree tooltip (PassiveTreeView:AddNodeTooltip).

-- Applies a tree state without recalculating.
-- tree: { classId, ascendClassId, nodes = { {id, ws}, ... } (ws: 0 normal, 1/2 weapon set),
--         attributes = { {id, attr}, ... } (1 Str, 2 Dex, 3 Int) }
-- Returns the ids PoB did not accept (unknown, or not connected under PoB's rules).
local function applyTree(tree)
	local spec = build.spec
	local classId = tonumber(tree.classId)
	local class = spec.tree.classes[classId]
	if not class then
		error("unknown class " .. tostring(tree.classId), 0)
	end
	local ascendClassId = tonumber(tree.ascendClassId) or 0
	if ascendClassId ~= 0 and not class.classes[ascendClassId] then
		error("unknown ascendancy " .. tostring(tree.ascendClassId), 0)
	end

	-- A freshly loaded spec starts with every node in normal mode; ResetNodes keeps old modes
	for _, node in pairs(spec.nodes) do
		node.allocMode = 0
	end
	-- Attribute choices first, like PassiveSpec:Load
	spec.hashOverrides = { }
	for _, a in ipairs(tree.attributes or { }) do
		local id, attr = tonumber(a.id), tonumber(a.attr)
		if id and attr and attr >= 1 and attr <= 3 and spec.nodes[id] then
			spec:SwitchAttributeNode(id, attr)
		end
	end
	local hashList, weaponSets, unknown = { }, { }, { }
	for _, n in ipairs(tree.nodes or { }) do
		local id = tonumber(n.id)
		if id and spec.nodes[id] then
			hashList[#hashList + 1] = id
			if n.ws == 1 or n.ws == 2 then
				weaponSets[id] = n.ws
			end
		else
			unknown[#unknown + 1] = n.id
		end
	end
	-- (No spec:AddUndoState(): the app keeps the tree's history, and PoB's undo states would keep
	-- every generation of overrides alive)
	spec:ImportFromNodeList(nil, classId, ascendClassId, 0, hashList, weaponSets, copyTable(spec.hashOverrides, true), { })
	build.itemsTab:UpdateSockets()
	api.dirty()
	local dropped = { }
	for _, id in ipairs(hashList) do
		if not spec.allocNodes[id] then
			dropped[#dropped + 1] = id
		end
	end
	return { unknown = api.array(unknown), dropped = api.array(dropped) }
end

-- True if PoB's spec already has this tree (class, ascendancy, nodes, weapon sets, attributes)
local function treeMatches(tree)
	local spec = build.spec
	if spec.curClassId ~= tonumber(tree.classId) or (spec.curAscendClassId or 0) ~= (tonumber(tree.ascendClassId) or 0) then
		return false
	end
	local wanted = { }
	for _, n in ipairs(tree.nodes or { }) do
		local id = tonumber(n.id)
		if not id then
			return false
		end
		wanted[id] = tonumber(n.ws) or 0
	end
	for id, node in pairs(spec.allocNodes) do
		-- Passives granted by items are not part of the app's tree
		if not (node.isGrantedPassive and node.isFreeAllocate) then
			if wanted[id] == nil or (node.allocMode or 0) ~= wanted[id] then
				return false
			end
			wanted[id] = nil
		end
	end
	if next(wanted) ~= nil then
		return false
	end
	local attributes = { }
	for _, a in ipairs(tree.attributes or { }) do
		attributes[tonumber(a.id)] = tonumber(a.attr)
	end
	for id, node in pairs(spec.allocNodes) do
		if node.isAttribute then
			local override = spec.hashOverrides[id]
			local attr = attributes[id]
			local option = attr and spec.tree.nodes[id] and spec.tree.nodes[id].options and spec.tree.nodes[id].options[attr]
			if (override == nil) ~= (option == nil) or (override and option and override.dn ~= option.dn) then
				return false
			end
		end
	end
	return true
end

-- Pushes the app's tree, recalculates and returns the sidebar state
function api.setTree(args)
	local result = applyTree(args)
	api.recalc()
	local state = api.state()
	state.tree = result
	return state
end

-- Opens a build for the app in one call: loads the XML (or a new build), applies the app's tree
-- and character level, and returns the sidebar state.
-- args: { xml?, name?, tree = {...}, level? }
function api.openBuild(args)
	local t0 = __host_time()
	api.loadBuild(args)
	local t1 = __host_time()
	-- The build's XML usually has the app's tree already (it is saved after tree changes)
	local result
	if args.tree and not treeMatches(args.tree) then
		result = applyTree(args.tree)
	end
	local changed = result ~= nil
	if args.level and (build.characterLevel ~= math.floor(tonumber(args.level) or 1) or build.characterLevelAutoMode) then
		changed = true
		build.characterLevel = math.min(math.max(math.floor(tonumber(args.level) or 1), 1), 100)
		build.characterLevelAutoMode = false
		if build.controls.levelScalingButton then
			build.controls.levelScalingButton.label = "Manual"
		end
		if build.controls.characterLevel then
			build.controls.characterLevel:SetText(build.characterLevel)
		end
		build.configTab:BuildModList()
	end
	if changed then
		api.recalc()
	end
	ConPrintf("openBuild: load %d ms, %s", t1 - t0, changed and ("tree/level + calculation " .. (__host_time() - t1) .. " ms") or "tree unchanged")
	local state = api.state()
	state.tree = result
	-- The build differs from its XML: the app saves it again
	state.changed = changed
	return state
end

-- Collects tooltip lines (PoB's Tooltip only needs AddLine / AddSeparator here)
local function newLineCollector()
	local tt = { lines = { } }
	function tt:AddLine(size, text)
		if text then
			for line in (text .. "\n"):gmatch("([^\n]*)\n") do
				self.lines[#self.lines + 1] = line
			end
		end
	end
	function tt:AddSeparator()
		self.lines[#self.lines + 1] = ""
	end
	return tt
end

-- Stat changes from allocating or removing a node, as in PoB's tree tooltip.
-- args: { id, path = { ids the app would allocate, target included } (optional),
--         allocMode = 0/1/2 (weapon set the app allocates with; optional) }
-- Returns { lines = {...}, count } with PoB's colour codes.
function api.nodeCompare(args)
	local spec = build.spec
	local node = spec.nodes[tonumber(args.id)]
	if not node then
		error("unknown node " .. tostring(args.id), 0)
	end
	local calcFunc, calcBase = build.calcsTab:GetMiscCalculator()
	local path
	if node.alloc then
		path = node.depends or { }
	elseif args.path then
		path = { }
		for _, id in ipairs(args.path) do
			local n = spec.nodes[tonumber(id)]
			if n and not n.alloc then
				path[#path + 1] = n
			end
		end
	else
		path = node.path or { }
	end
	local pathLength = #path
	local pathNodes = { }
	for _, n in pairs(path) do
		pathNodes[n] = true
	end
	local isGranted = build.calcsTab.mainEnv.grantedPassives[node.id]
	local allocMode = tonumber(args.allocMode) or 0

	-- Nodes allocated with weapon set points only count for skills used with that set: give the
	-- nodes being added the mode the app allocates them with (restored afterwards). Keystones,
	-- jewel sockets and ascendancy nodes always use normal points, as in PassiveSpec:AllocNode.
	local saved = { }
	local function setModes(set)
		if allocMode <= 0 then
			return
		end
		for n in pairs(set) do
			if saved[n] == nil then
				saved[n] = n.allocMode or 0
			end
			local normal = node.ascendancyName or n.type == "Keystone" or n.type == "Socket" or n.containJewelSocket
			n.allocMode = normal and 0 or allocMode
		end
	end

	local tt = newLineCollector()
	local count = 0
	local ok, err = pcall(function()
		local nodeOutput, pathOutput
		if node.alloc then
			nodeOutput = calcFunc({ removeNodes = { [node] = true } })
			if pathLength > 1 then
				pathOutput = calcFunc({ removeNodes = pathNodes })
			end
		elseif isGranted then
			nodeOutput = calcFunc({ removeNodes = { [node.id] = true } })
		else
			local single = { [node] = true }
			setModes(single)
			nodeOutput = calcFunc({ addNodes = single })
			if pathLength > 1 then
				setModes(pathNodes)
				pathOutput = calcFunc({ addNodes = pathNodes })
			end
		end
		local header = node.alloc and "^7Unallocating this node will give you:"
			or isGranted and "^7This node is granted by an item. Removing it will give you:"
			or "^7Allocating this node will give you:"
		count = build:AddStatComparesToTooltip(tt, calcBase, nodeOutput, header)
		if pathLength > 1 and not isGranted and (#(node.intuitiveLeapLikesAffecting or { }) == 0 or node.alloc) then
			count = count + build:AddStatComparesToTooltip(tt, calcBase, pathOutput,
				node.alloc and "^7Unallocating this node and all nodes depending on it will give you:"
				or "^7Allocating this node and all nodes leading to it will give you:", pathLength)
		end
		if count == 0 then
			tt:AddLine(14, isGranted and "^7This node is granted by an item. Removing it will cause no changes"
				or string.format("^7No changes from %s this node.", node.alloc and "unallocating" or "allocating"))
		end
	end)
	for n, mode in pairs(saved) do
		n.allocMode = mode
	end
	if not ok then
		error(err, 0)
	end
	return { lines = api.array(tt.lines), count = count }
end
