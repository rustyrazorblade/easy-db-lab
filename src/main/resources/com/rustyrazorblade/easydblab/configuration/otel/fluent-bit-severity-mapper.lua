-- Maps journald syslog PRIORITY to severity text and drops noise fields.
-- Syslog: 0=emerg, 1=alert, 2=crit, 3=err, 4=warning, 5=notice, 6=info, 7=debug

local KEEP = {
    MESSAGE = true,
    PRIORITY = true,
}

local SEVERITY = {
    ["0"] = "FATAL", ["1"] = "FATAL",
    ["2"] = "ERROR", ["3"] = "ERROR",
    ["4"] = "WARN",
    ["5"] = "INFO",  ["6"] = "INFO",
    ["7"] = "DEBUG",
}

-- Cassandra's logback lines reach VictoriaLogs twice: once from the OTel Java agent, which adds
-- severity, logger name and the resource labels, and once from this journal scrape, which adds
-- none of that. Only the duplicate is dropped.
--
-- The unit is NOT excluded wholesale, and that is the point of doing this here rather than with a
-- grep filter. Cassandra writes to the journal outside logback as well - JVM CompileCommand output
-- at startup is visible on the nodes today, and a JVM crash, an OutOfMemoryError handler or
-- anything logged before logback initialises would arrive the same way. The agent cannot see any of
-- it, so the journal is the only path it has. Losing a crash log to save duplicate INFO lines is a
-- bad trade.
--
-- A logback line is recognised by its leading level token. Everything else from the unit is kept.
local function is_duplicate_of_agent(record)
    if record["SYSTEMD_UNIT"] ~= "cassandra.service" then
        return false
    end

    local message = record["MESSAGE"]
    if type(message) ~= "string" then
        return false
    end

    -- "INFO  [main] 2026-.. .." from Cassandra, "WARNING 2026-.. .." from the AxonOps agent.
    return string.match(message, "^%u%u+%s") ~= nil
end

function process(tag, timestamp, record)
    if is_duplicate_of_agent(record) then
        return -1, timestamp, record
    end

    local new = {}
    for k, v in pairs(record) do
        if KEEP[k] then new[k] = v end
    end
    local p = tostring(record["PRIORITY"] or "")
    new["severity"] = SEVERITY[p] or "INFO"
    new["service"] = record["SYSLOG_IDENTIFIER"] or ""
    new["systemd_unit"] = record["SYSTEMD_UNIT"] or ""
    new["source"] = "journald"
    new["host.name"] = record["HOSTNAME"] or ""
    return 1, timestamp, new
end
