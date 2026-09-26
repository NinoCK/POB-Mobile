-- Node power: PoB's tree heat map and power report (CalcsTab:PowerBuilder, TreeTab). PoB builds the
-- powers in a coroutine over several frames; the app runs it in steps between its other calls.

local progress = 0

-- The heat map's statistics (TreeTab.powerStatList): the first one is PoB's combined
-- offence / defence map, the others a single statistic.
function api.powerStats()
	local out = api.array()
	for i, stat in ipairs(build.treeTab.powerStatList) do
		out[i] = { label = stat.label, stat = stat.stat }
	end
	return out
end

-- Starts building the powers. args: { stat = index in powerStats (1-based), maxDepth = n or nil }
-- (TreeTab:SetPowerCalc and its depth selection)
function api.powerStart(args)
	local calcsTab = build.calcsTab
	local list = build.treeTab.powerStatList
	local stat = list[tonumber(args.stat) or 1] or list[1]
	calcsTab.powerStat = stat
	calcsTab.nodePowerMaxDepth = tonumber(args.maxDepth)
	calcsTab.powerBuildFlag = true
	progress = 0
	build.powerBuilderProgressCallback = function(percent)
		progress = percent
	end
	calcsTab:BuildPower()
	return true
end

local function result()
	local calcsTab = build.calcsTab
	local max = calcsTab.powerMax or { }
	local out = {
		single = calcsTab.powerStat and calcsTab.powerStat.stat and true or false,
		max = { singleStat = max.singleStat or 0, offence = max.offence or 0, defence = max.defence or 0 },
		nodes = api.array(),
		report = api.array(),
	}
	for id, node in pairs(build.spec.nodes) do
		local p = node.power
		if p and (p.singleStat or p.offence) then
			out.nodes[#out.nodes + 1] = { id = id, single = p.singleStat, offence = p.offence, defence = p.defence }
		end
	end
	if out.single then
		-- PoB's power report: the passives by power for the statistic, formatted like its sidebar
		local report = build.treeTab:BuildPowerReportList(calcsTab.powerStat)
		table.sort(report, function(a, b)
			if a.power ~= b.power then
				return a.power > b.power
			end
			return a.name < b.name
		end)
		for i, entry in ipairs(report) do
			out.report[i] = {
				id = entry.id,
				name = entry.name,
				type = entry.type,
				power = entry.power,
				powerStr = entry.powerStr,
				pathPowerStr = entry.pathPowerStr,
				pathDist = entry.pathDist,
				allocated = entry.allocated and true or false,
			}
		end
	end
	return out
end

-- Continues building for about args.ms milliseconds (PoB yields every 100 ms).
-- Returns { done = false, progress = % } or { done = true, result = { single, max, nodes, report } }.
function api.powerStep(args)
	local calcsTab = build.calcsTab
	local deadline = GetTime() + (tonumber(args.ms) or 150)
	repeat
		calcsTab:BuildPower()
	until not calcsTab.powerBuilder or GetTime() >= deadline
	if calcsTab.powerBuilder then
		return { done = false, progress = progress }
	end
	return { done = true, result = result() }
end
