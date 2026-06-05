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

function process(tag, timestamp, record)
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
