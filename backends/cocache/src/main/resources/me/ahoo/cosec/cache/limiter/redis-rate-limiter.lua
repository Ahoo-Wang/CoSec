-- Sliding-window (interpolated counters) rate limiter script.
--
-- KEYS[1] - the bucket: a hash of the window it counts in (w), that window's count (c) and the previous one's (p)
-- ARGV[1] - quota per window (permitsPerSecond * windowSeconds)
-- ARGV[2] - window size in milliseconds, at most a day
--
-- Returns {allowed, estimate, resetAfter}: allowed is 1 when the request is allowed (and counted), 0 when the quota is
-- exceeded; estimate is the weighted count before this request, in thousandths (Redis truncates Lua numbers);
-- resetAfter is the milliseconds left in the current window.
--
-- The time is Redis's own (TIME), so instances whose clocks differ count in the same window, and the bucket is one key,
-- so the script touches nothing it did not declare and runs on a Redis Cluster as on one node. The check and the count
-- happen atomically, so the limit is enforced cluster-wide. Every write sets the key's expiry, two windows: no key is
-- left without one, whatever it held before.

local quota = tonumber(ARGV[1])
local windowMs = tonumber(ARGV[2])
if quota == nil or windowMs == nil or windowMs < 1 or windowMs > 86400000 then
  return redis.error_reply('CoSec rate limiter: invalid quota or window')
end
local time = redis.call('TIME')
local nowMs = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local window = math.floor(nowMs / windowMs)
local stored = redis.call('HMGET', KEYS[1], 'w', 'c', 'p')
local counted = tonumber(stored[1])
local current = tonumber(stored[2]) or 0
local previous = tonumber(stored[3]) or 0
if counted == nil or counted < window - 1 then
  current, previous = 0, 0
elseif counted == window - 1 then
  current, previous = 0, current
elseif counted > window then
  -- Redis's clock went back (a failover to a node behind): keep counting in the window already counted.
  window = counted
end
local elapsed = math.min(math.max(nowMs - window * windowMs, 0), windowMs - 1)
local estimate = previous * ((windowMs - elapsed) / windowMs) + current
local scaledEstimate = math.floor(estimate * 1000)
local resetAfter = windowMs - elapsed
if estimate >= quota then
  return {0, scaledEstimate, resetAfter}
end
redis.call('HSET', KEYS[1], 'w', window, 'c', current + 1, 'p', previous)
redis.call('PEXPIRE', KEYS[1], windowMs * 2)
return {1, scaledEstimate, resetAfter}
