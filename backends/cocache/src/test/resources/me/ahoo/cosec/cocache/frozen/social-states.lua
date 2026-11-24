-- Frozen copy of social-states.lua as of 8.0.0-beta.9 (RFC 0014 R6, SNP-26): FrozenScriptsTest runs it beside the current script on one key.
-- Replace it only at RC1 (with RC1's text) and at GA (with GA's), then never within 8.x: see FrozenLayout.
-- How many social sign-ins were started in the deployment, to bound the states in Redis (DR1-D-9).
-- KEYS[1]: the count, one hash whose fields are windows of the states' lifetime and whose values are the starts in each:
--          one key, so the script runs on a Redis Cluster as on one node.
-- ARGV[1]: the window, in seconds: a state's lifetime, so the starts of this window and the last hold every live state.
-- ARGV[2]: the most states the deployment may hold.
-- Returns 1 when this start is counted, 0 when it would pass the bound: then nothing is counted.
-- The time is Redis's own (TIME), so every instance counts in the same window; the key expires two windows after the
-- last start, and older windows are dropped as it goes, so it leaves nothing behind.
local window = tonumber(ARGV[1])
local bound = tonumber(ARGV[2])
local now = redis.call('TIME')
local current = math.floor(tonumber(now[1]) / window)
local previous = tonumber(redis.call('HGET', KEYS[1], tostring(current - 1)) or '0')
local counted = tonumber(redis.call('HGET', KEYS[1], tostring(current)) or '0')
for _, field in ipairs(redis.call('HKEYS', KEYS[1])) do
    if tonumber(field) < current - 1 then
        redis.call('HDEL', KEYS[1], field)
    end
end
if counted + previous >= bound then
    redis.call('PEXPIRE', KEYS[1], window * 2000)
    return 0
end
redis.call('HINCRBY', KEYS[1], tostring(current), 1)
redis.call('PEXPIRE', KEYS[1], window * 2000)
return 1
