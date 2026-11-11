--[[
Sliding window counter, evaluated atomically.

KEYS[1]  hash: ws = start of the current window (ms), c = admitted in it, p = admitted in the previous one
ARGV[1]  limit (requests per sliding window, >= 1)
ARGV[2]  window_ms (W)
ARGV[3]  now_ms override ('' in production -> Redis TIME)

Returns  {allowed (0|1), remaining, retry_after_ms, reset_after_ms}

Estimate of the requests in the last W ms, with e = now - ws (0 <= e < W):

    estimate = p * (W - e) / W + c

i.e. the previous window's requests are assumed to be spread evenly, and the fraction of them still
inside the sliding window is counted. A request is admitted when estimate + 1 <= limit. Multiplying
by W gives an exact integer test, with no floating-point rounding:

    p * (W - e) + (c + 1) * W <= limit * W

Rule bounds keep every product below 2^53 (exact in Lua doubles).
]]
local GRACE_MS = 1000   -- TTL slack so a key never expires while its counts still matter
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

local ws = math.floor(now / window) * window
local cur, prev = 0, 0

local state = redis.call('HMGET', key, 'ws', 'c', 'p')
local stored_ws = tonumber(state[1])
if stored_ws ~= nil then
  local stored_cur = tonumber(state[2]) or 0
  local stored_prev = tonumber(state[3]) or 0
  if stored_ws == ws then
    cur, prev = stored_cur, stored_prev                    -- same window
  elseif stored_ws == ws - window then
    cur, prev = 0, stored_cur                              -- rolled into the next window
  elseif stored_ws > ws then
    ws, cur, prev = stored_ws, stored_cur, stored_prev     -- clock moved back: never rewind
  end
  -- stored_ws older than the previous window: nothing from it is inside the sliding window.
end

local elapsed = now - ws
if elapsed < 0 then elapsed = 0 end
local budget = limit * window
local used = prev * (window - elapsed) + cur * window      -- estimate * W

local allowed = 0
local retry_after = 0
if used + window <= budget then
  allowed = 1
  cur = cur + 1
  used = used + window
  redis.call('HSET', key, 'ws', ws, 'c', cur, 'p', prev)
  -- `cur` stays relevant until the end of the NEXT window, when it has fully slid out.
  redis.call('PEXPIRE', key, 2 * window - elapsed + GRACE_MS)
else
  -- Earliest time t >= 1 ms from now at which one more request fits (assuming no other admissions).
  local left_in_window = window - elapsed
  if cur < limit and prev > 0 then
    -- Still in this window: need p * (W - e - t) + (c + 1) * W <= limit * W
    local t = math.ceil((left_in_window * prev - (limit - cur - 1) * window) / prev)
    if t < left_in_window then
      retry_after = t
    end
  end
  if retry_after == 0 then
    -- In the next window, at offset e2: previous = c, current = 0.
    -- Need c * (W - e2) + W <= limit * W  ->  e2 >= W * (c - limit + 1) / c
    local e2 = 0
    if cur > 0 then
      e2 = math.ceil(window * (cur - limit + 1) / cur)
      if e2 < 0 then e2 = 0 end
    end
    retry_after = left_in_window + e2
  end
end

local reset_after = 0                                      -- until the estimate is back to 0
if cur > 0 then
  reset_after = 2 * window - elapsed
elseif prev > 0 then
  reset_after = window - elapsed
end

local remaining = math.floor((budget - used) / window)
if remaining < 0 then remaining = 0 end
return {allowed, remaining, retry_after, reset_after}
