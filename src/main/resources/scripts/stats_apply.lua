--[[
Applies one statistics batch for one policy to this instance's shard, at most once.

KEYS[1]  shard hash  <prefix>:stats:{<policyId>}:<instanceId>
         fields: allowed, rejected, fail_open, fail_closed, boot, seq, last_flush_ms
KEYS[2]  index set   <prefix>:stats:{<policyId>}:instances
ARGV[1]  instance id      ARGV[2] boot id      ARGV[3] batch sequence
ARGV[4..7] deltas: allowed, rejected, fail_open, fail_closed
ARGV[8]  TTL ms (refreshed on every flush)

Returns 1 if applied, 0 if this (boot, seq) was already applied (a retry after an ambiguous failure).
Both keys share the {policyId} hash tag, so this stays single-slot in Redis Cluster.
]]
local seq = tonumber(ARGV[3])
local stored = redis.call('HMGET', KEYS[1], 'boot', 'seq')
if stored[1] == ARGV[2] and tonumber(stored[2]) ~= nil and seq <= tonumber(stored[2]) then
  return 0
end
redis.call('HINCRBY', KEYS[1], 'allowed', ARGV[4])
redis.call('HINCRBY', KEYS[1], 'rejected', ARGV[5])
redis.call('HINCRBY', KEYS[1], 'fail_open', ARGV[6])
redis.call('HINCRBY', KEYS[1], 'fail_closed', ARGV[7])
local t = redis.call('TIME')
redis.call('HSET', KEYS[1], 'boot', ARGV[2], 'seq', seq,
           'last_flush_ms', tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000))
redis.call('PEXPIRE', KEYS[1], ARGV[8])
redis.call('SADD', KEYS[2], ARGV[1])
redis.call('PEXPIRE', KEYS[2], ARGV[8])
return 1
