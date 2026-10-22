-- Expires one hold whose time is up (README section 9.3).
--
-- Like release, with two extra conditions: the hold has to still be ACTIVE, and its expiry has to
-- have passed. Both are checked here rather than by the caller, which is what lets the sweeper run
-- on every pod at once with no leader election: a hold that another pod expired a millisecond
-- earlier, or that its owner turned into a checkout in the meantime, is a no-op here.
--
-- A frozen hold is never expired. The SAGA owns it and always ends in either a commit or a
-- release, so expiring it would take seats away from an order that is still being paid for.
--
-- KEYS[1] seats hash
-- KEYS[2] seat state version
-- KEYS[3] available counter
-- KEYS[4] expiry sorted set
-- KEYS[5] userhold
-- KEYS[6] hold hash
-- KEYS[7] seat events stream
--
-- ARGV[1] holdId
-- ARGV[2] now              epoch millis, from the injected clock
-- ARGV[3] stream maxlen
-- ARGV[4] eventId
--
-- Returns a JSON object whose status is OK, NOT_FOUND, NOT_DUE or WRONG_STATE.

local seats_key = KEYS[1]
local version_key = KEYS[2]
local available_key = KEYS[3]
local expiry_key = KEYS[4]
local userhold_key = KEYS[5]
local hold_key = KEYS[6]
local stream_key = KEYS[7]

local hold_id = ARGV[1]
local now = tonumber(ARGV[2])
local stream_maxlen = ARGV[3]
local event_id = ARGV[4]

local stored = redis.call('HMGET', hold_key, 'seats', 'status', 'expiresAt')
local seats_csv = stored[1]
local status = stored[2]
local expires_at = tonumber(stored[3])

if not seats_csv then
  -- The hold hash is gone but the sorted set still names it: drop the entry so the sweeper
  -- stops finding it.
  redis.call('ZREM', expiry_key, hold_id)
  return cjson.encode({ status = 'NOT_FOUND' })
end
if status ~= 'ACTIVE' then
  redis.call('ZREM', expiry_key, hold_id)
  return cjson.encode({ status = 'WRONG_STATE', holdStatus = status })
end
if expires_at > now then
  return cjson.encode({ status = 'NOT_DUE', expiresAt = expires_at })
end

local seat_ids = {}
for seat_id in string.gmatch(seats_csv, '[^,]+') do
  seat_ids[#seat_ids + 1] = seat_id
end

local freed = 0
local held_value = 'H:' .. hold_id
for i = 1, #seat_ids do
  if redis.call('HGET', seats_key, seat_ids[i]) == held_value then
    redis.call('HSET', seats_key, seat_ids[i], 'A')
    freed = freed + 1
  end
end
if freed > 0 then
  redis.call('INCRBY', available_key, freed)
end

redis.call('HSET', hold_key, 'status', 'EXPIRED')
redis.call('ZREM', expiry_key, hold_id)
if redis.call('GET', userhold_key) == hold_id then
  redis.call('DEL', userhold_key)
end

local version = redis.call('INCR', version_key)
redis.call('XADD', stream_key, 'MAXLEN', '~', stream_maxlen, '*',
  'type', 'SeatsReleased',
  'eventId', event_id,
  'holdId', hold_id,
  'seats', seats_csv,
  'version', version,
  'occurredAt', now)

return cjson.encode({ status = 'OK', holdId = hold_id, version = version, freedSeats = freed })
