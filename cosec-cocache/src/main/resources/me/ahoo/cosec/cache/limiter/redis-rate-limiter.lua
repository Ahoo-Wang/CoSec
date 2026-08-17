-- Sliding-window (interpolated counters) rate limiter script.
--
-- KEYS[1] - current window counter key
-- KEYS[2] - previous window counter key
-- ARGV[1] - quota per window (permitsPerSecond * windowSeconds)
-- ARGV[2] - window size in milliseconds
-- ARGV[3] - current epoch millis
--
-- Returns 1 when the request is allowed (and counted), 0 when the quota is exceeded.
-- The check and the count happen atomically, so the limit is enforced cluster-wide.
--
-- Note: the two keys are not wrapped in a Redis Cluster hashtag; CoSec has no
-- cluster support elsewhere, and tagging would pin all counters to one slot.

local current = tonumber(redis.call('GET', KEYS[1]) or '0')
local previous = tonumber(redis.call('GET', KEYS[2]) or '0')
local windowMs = tonumber(ARGV[2])
local nowMs = tonumber(ARGV[3])
local previousWeight = (windowMs - (nowMs % windowMs)) / windowMs
local estimate = previous * previousWeight + current
if estimate >= tonumber(ARGV[1]) then
  return 0
end
redis.call('INCR', KEYS[1])
if current == 0 then
  redis.call('PEXPIRE', KEYS[1], windowMs * 2)
end
return 1
