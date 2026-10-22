-- Gives the seats of a hold back (README section 9.2).
--
-- Used by the user who releases a hold and, later, by the SAGA compensating a checkout. Which
-- statuses are allowed is the caller's decision and arrives in ARGV: a user may only release a
-- hold that is still ACTIVE, while a compensation also has to release one that was frozen into
-- CHECKOUT.
--
-- Idempotent by status: a hold that is already RELEASED, EXPIRED or CONSUMED is reported as such
-- and nothing is touched, so a retried compensation cannot free seats twice and make the available
-- counter lie.
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
-- ARGV[2] now                epoch millis
-- ARGV[3] stream maxlen
-- ARGV[4] eventId
-- ARGV[5] userId             the caller, or an empty string to skip the ownership check
-- ARGV[6] allowed statuses   comma separated, for example ACTIVE or ACTIVE,CHECKOUT
--
-- Returns a JSON object whose status is OK, NOT_FOUND, FORBIDDEN or WRONG_STATE.

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
local caller_id = ARGV[5]
local allowed = ARGV[6]

local stored = redis.call('HMGET', hold_key, 'userId', 'seats', 'status')
local owner_id = stored[1]
local seats_csv = stored[2]
local status = stored[3]

if not owner_id then
  return cjson.encode({ status = 'NOT_FOUND' })
end
if caller_id ~= '' and owner_id ~= caller_id then
  return cjson.encode({ status = 'FORBIDDEN' })
end
if not string.find(',' .. allowed .. ',', ',' .. status .. ',', 1, true) then
  return cjson.encode({ status = 'WRONG_STATE', holdStatus = status })
end

local seat_ids = {}
for seat_id in string.gmatch(seats_csv, '[^,]+') do
  seat_ids[#seat_ids + 1] = seat_id
end

-- Only the seats this hold still owns go back. A seat whose value names another hold was already
-- taken over, which the state machine does not allow today, and freeing it would hand somebody
-- else's seat away.
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

redis.call('HSET', hold_key, 'status', 'RELEASED')
redis.call('ZREM', expiry_key, hold_id)
-- Only if it still points at this hold: the user may already hold something else.
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
