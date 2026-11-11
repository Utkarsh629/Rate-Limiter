--[[
Fixed window counter, evaluated atomically.

KEYS[1]  window hash: w = window index (floor(now / window_ms)), c = requests admitted in it
ARGV[1]  limit       (requests per window, >= 1)
ARGV[2]  window_ms
ARGV[3]  now_ms override ('' in production -> Redis TIME)

Returns  {allowed (0|1), remaining, retry_after_ms, reset_after_ms}

Windows are aligned to the epoch: window k covers [k * window_ms, (k + 1) * window_ms).

One key per (policy, subject), not one key per window: the window index is only known inside the
script (it comes from Redis TIME), and a script must declare every key it touches up front for
Redis Cluster. The stored index tells the script whether the stored count belongs to the current
window; a count from an earlier window is treated as 0, so a late expiry is harmless.
An EARLY expiry would not be: it would erase a count that still applies. Inside a script Redis
computes PEXPIRE from the time cached when the script started, while TIME reads the live clock, so
the TTL gets GRACE_MS of slack and can never fire before the window has ended.
]]
local GRACE_MS = 1000
local key = KEYS[1]
local limit = tonumber(ARGV[1])
local window = tonumber(ARGV[2])

local now
if ARGV[3] ~= '' then
  now = tonumber(ARGV[3])
else
  local t = redis.call('TIME')
  now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
end

local index = math.floor(now / window)

local state = redis.call('HMGET', key, 'w', 'c')
local stored_index = tonumber(state[1])
local count = 0
local same_window = false
if stored_index ~= nil then
  if stored_index == index then
    count = tonumber(state[2]) or 0
    same_window = true
  elseif stored_index > index then
    -- The clock moved back across a boundary: keep counting in the later window rather than
    -- opening an earlier one with a fresh quota.
    index = stored_index
    count = tonumber(state[2]) or 0
    same_window = true
  end
  -- stored_index < index: an older window; its count no longer applies.
end

local reset_after = (index + 1) * window - now   -- time until this window ends
local allowed = 0
local retry_after = 0
if count < limit then
  allowed = 1
  if same_window then
    count = redis.call('HINCRBY', key, 'c', 1)
  else
    count = 1
    redis.call('HSET', key, 'w', index, 'c', 1)
    -- Expire after the window ends (plus grace): from then on the stored count is irrelevant.
    redis.call('PEXPIRE', key, reset_after + GRACE_MS)
  end
else
  -- Rejections write nothing; the next window starts exactly when this one ends.
  retry_after = reset_after
end

return {allowed, limit - count, retry_after, reset_after}
