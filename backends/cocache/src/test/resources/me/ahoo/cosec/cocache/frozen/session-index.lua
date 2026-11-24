-- Frozen copy of session-index.lua as of 8.0.0-beta.9 (RFC 0014 R6, SNP-26): FrozenScriptsTest runs it beside the current script on one key.
-- Replace it only at RC1 (with RC1's text) and at GA (with GA's), then never within 8.x: see FrozenLayout.
-- The sessions of one principal in one tenant, in the order they began, to keep the most recent of them (DR1-G-2).
-- KEYS[1]: the index, one sorted set of session ids scored by when each began: one key, so the script runs on a Redis
--          Cluster as on one node. The sessions' own records are other keys, which the caller deletes.
-- ARGV[1]: the id of the session that begins now.
-- ARGV[2]: how many sessions the principal keeps.
-- ARGV[3]: the least time the index must still live, in milliseconds: the longest any of its sessions can.
-- ARGV[4]: the root of the session that begins now (IDN-36), or '': when it is in this index, it is begun again now, so
--          switching back and forth between two tenants never pushes out the login the switched sessions depend on.
-- Returns the ids pushed out, oldest first.
-- The time is Redis's own (TIME), so every instance orders sessions alike; a session that begins now is always the
-- newest, even in the millisecond of another, so it is never the one pushed out.
local keep = tonumber(ARGV[2])
local life = tonumber(ARGV[3])
local now = redis.call('TIME')
local score = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)
local newest = redis.call('ZREVRANGE', KEYS[1], 0, 0, 'WITHSCORES')
if newest[2] ~= nil and tonumber(newest[2]) >= score then
    score = tonumber(newest[2]) + 1
end
if ARGV[4] ~= '' then
    redis.call('ZADD', KEYS[1], 'XX', score, ARGV[4])
    score = score + 1
end
redis.call('ZADD', KEYS[1], score, ARGV[1])
local pushed = {}
local over = redis.call('ZCARD', KEYS[1]) - keep
if over > 0 then
    local popped = redis.call('ZPOPMIN', KEYS[1], over)
    for i = 1, #popped, 2 do
        pushed[#pushed + 1] = popped[i]
    end
end
if redis.call('PTTL', KEYS[1]) < life then
    redis.call('PEXPIRE', KEYS[1], life)
end
return pushed
