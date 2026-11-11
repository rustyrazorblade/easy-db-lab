-- Unit tests for the Fluent Bit journald filter.
--
-- The guard these exist for: Cassandra's logback lines arrive twice, once from the OTel Java agent
-- and once from this journal scrape, and only the duplicate should be dropped. Excluding the whole
-- unit would have been one line of grep filter and would also have thrown away JVM crash output,
-- OOM handler output and anything logged before logback starts - none of which the agent can see.
-- The cases below pin both halves of that.
--
-- Run: ./gradlew testFluentBitFilter

dofile("src/main/resources/com/rustyrazorblade/easydblab/configuration/otel/fluent-bit-severity-mapper.lua")

local DROP = -1

local cases = {
    -- Dropped: the agent already delivers these, with severity and logger attached.
    {"cassandra.service", "INFO  [main] 2026-09-07 05:40:08,831 CassandraDaemon.java:1 - started", true, "logback INFO"},
    {"cassandra.service", "WARN  [ScheduledTasks:1] 2026-09-07 05:40:08,831 Foo.java:9 - slow", true, "logback WARN"},
    {"cassandra.service", "ERROR [main] 2026-09-07 05:40:08,831 Foo.java:9 - broke", true, "logback ERROR"},
    {"cassandra.service", "WARNING 2026-09-07 05:40:08,831 getAttribute for x took 673ms", true, "AxonOps agent WARNING"},

    -- Kept: written outside logback, so the journal is the only path these have.
    {"cassandra.service", "CompileCommand: dontinline org/apache/cassandra/db/Columns", false, "JVM CompileCommand"},
    {"cassandra.service", "# A fatal error has been detected by the Java Runtime Environment:", false, "JVM crash header"},
    {"cassandra.service", "java.lang.OutOfMemoryError: Java heap space", false, "OutOfMemoryError"},
    {"cassandra.service", "Killed process 14129 (java)", false, "OOM killer"},
    {"cassandra.service", "", false, "empty message"},

    -- Kept: the exclusion is scoped to one unit, not to a message shape.
    {"cassandra-sidecar.service", "INFO  [main] 2026-09-07 sidecar started", false, "another unit's logback"},
    {"k3s.service", "INFO  [main] k3s says something", false, "k3s"},
    {"", "INFO  [main] no unit at all", false, "record with no unit"},
}

local failed = 0

for _, case in ipairs(cases) do
    local unit, message, should_drop, label = case[1], case[2], case[3], case[4]
    local code = process("journald", 0, {SYSTEMD_UNIT = unit, MESSAGE = message, PRIORITY = "6"})
    local dropped = code == DROP

    if dropped == should_drop then
        print("ok   - " .. label .. (should_drop and " is dropped" or " is kept"))
    else
        print("FAIL - " .. label .. ": expected " .. (should_drop and "drop" or "keep") ..
            ", got code " .. tostring(code))
        failed = failed + 1
    end
end

-- A kept record must still be mapped, not passed through raw.
local code, _, mapped = process("journald", 0,
    {SYSTEMD_UNIT = "k3s.service", MESSAGE = "hello", PRIORITY = "4", HOSTNAME = "db0", SYSLOG_IDENTIFIER = "k3s"})
if code ~= DROP and mapped["severity"] == "WARN" and mapped["source"] == "journald" and mapped["service"] == "k3s" then
    print("ok   - a kept record is still severity-mapped")
else
    print("FAIL - a kept record lost its mapping")
    failed = failed + 1
end

print("")
print(#cases + 1 .. " assertions, " .. failed .. " failed")
os.exit(failed == 0 and 0 or 1)
