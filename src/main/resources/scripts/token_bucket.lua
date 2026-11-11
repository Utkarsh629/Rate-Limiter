--[[
Token bucket, evaluated atomically.

KEYS[1]  bucket hash: u = stored tokens in units, ts = last refill time (ms)
ARGV[1]  capacity        (tokens, >= 1)
ARGV[2]  refill_tokens   (tokens added per refill period, >= 1)
ARGV[3]  period_ms       (refill period, ms)
ARGV[4]  now_ms override ('' in production -> Redis TIME)

Returns  {allowed (0|1), remaining, retry_after_ms, reset_after_ms}

Exact integer arithmetic: one token = period_ms "units", and the bucket gains refill_tokens units
per millisecond. A rate such as 20 tokens / 60 000 ms is then 20 units/ms instead of a repeating
decimal, so refill never drifts. Rule bounds keep every value below 2^53 (exact in Lua doubles).
]]
local GRACE_MS = 1000   -- TTL slack; see the PEXPIRE comment below
local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill = tonumber(ARGV[2])
local period = tonumber(ARGV[3])

local now
if ARGV[4] ~= '' then
  now = tonumber(ARGV[4])
else
  local t = redis.call('TIME')
  now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
end

local full = capacity * period          -- a full bucket, in units
local cost = period                     -- one token, in units

local state = redis.call('HMGET', key, 'u', 'ts')
local units = tonumber(state[1])
local ts = tonumber(state[2])
if units == nil or ts == nil then
  -- Missing (never used, or expired after refilling completely) is identical to a full bucket.
  -- Initialisation happens inside this script, so concurrent first requests cannot race.
  units = full
  ts = now
end

-- Refill for the time elapsed since the last update. If the clock moved backwards, add nothing
-- and keep the later timestamp, so tokens are never created by clock movement.
if now > ts then
  local missing = full - units
  if missing > 0 then
    local elapsed = now - ts
    local ms_to_full = math.ceil(missing / refill)
    if elapsed >= ms_to_full then
      units = full
    else
      units = units + elapsed * refill
    end
  end
  ts = now
end

local allowed = 0
local retry_after = 0
if units >= cost then
  allowed = 1
  units = units - cost
  -- Write only on success. A rejected request leaves the stored (units, ts) pair unchanged: the
  -- refill computed above is a pure function of it, so nothing is lost by not saving it.
  redis.call('HSET', key, 'u', units, 'ts', ts)
  -- The key may disappear once the bucket would be full again, because a missing key already
  -- means "full"; idle clients cost no memory. It must never disappear EARLIER (that would hand out
  -- tokens not yet refilled). Redis computes PEXPIRE from the time cached at script start while
  -- TIME reads the live clock, so add GRACE_MS of slack.
  redis.call('PEXPIRE', key, math.ceil((full - units) / refill) + GRACE_MS)
else
  retry_after = math.ceil((cost - units) / refill)
end

local reset_after = math.ceil((full - units) / refill)
local remaining = math.floor(units / period)
return {allowed, remaining, retry_after, reset_after}
