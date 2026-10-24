-- Turns a frozen hold into a sale (README sections 9.2 and 9.6).
--
-- This is step 4 of the commit procedure, and it runs after the Postgres transaction that made the
-- sale a fact. By the time it runs the seats are already sold in the books; what is left is to make
-- the seat map agree, so that everybody watching it sees S instead of H and the hold stops being
-- something a compensation could release.
--
-- The order matters and it is not negotiable. Postgres first, Redis second: the primary key of
-- seat_sale is the guard that makes overselling impossible, and a seat map that said S before the
-- row existed would be a promise the books had not made yet. If this script never runs because the
-- pod died, the seats stay held in Redis and nobody else can take them, and the reconciler applies
-- it on its next pass. There is no interleaving in which a seat is sold twice.
--
-- Idempotent for the same order, because the reconciler and a redelivered command can both ask for
-- it: a hold already CONSUMED by this orderId answers OK and changes nothing. A hold consumed by a
-- different order is the one case that must fail, and it cannot happen without the Postgres guard
-- having failed first.
--
-- KEYS[1] seats hash            seatId -> A | H:<holdId> | S
-- KEYS[2] seat state version    monotonic counter of the seat map
-- KEYS[3] checkout sorted set   CHECKOUT holdId scored by the instant it was frozen
-- KEYS[4] userhold              the active hold of this user for this event
-- KEYS[5] hold hash             the hold itself
-- KEYS[6] seat events stream    what the relay forwards to Kafka
--
-- ARGV[1] holdId
-- ARGV[2] orderId               the order the seats were sold to
-- ARGV[3] now                   epoch millis, from the injected clock
-- ARGV[4] stream maxlen
-- ARGV[5] eventId
--
-- Returns a JSON object whose status is OK, NOT_FOUND, ALREADY_SOLD or WRONG_STATE.

local seats_key = KEYS[1]
local version_key = KEYS[2]
local checkout_key = KEYS[3]
local userhold_key = KEYS[4]
local hold_key = KEYS[5]
local stream_key = KEYS[6]

local hold_id = ARGV[1]
local order_id = ARGV[2]
local now = tonumber(ARGV[3])
local stream_maxlen = ARGV[4]
local event_id = ARGV[5]

local stored = redis.call('HMGET', hold_key, 'seats', 'status', 'orderId')
local seats_csv = stored[1]
local status = stored[2]
local sold_to = stored[3]

if not seats_csv then
  -- The hold hash is gone but something still named it. Drop the checkout entry so the reconciler
  -- stops finding it; the sale itself is safe, because Postgres is where it lives.
  redis.call('ZREM', checkout_key, hold_id)
  return cjson.encode({ status = 'NOT_FOUND' })
end

if status == 'CONSUMED' then
  if sold_to == order_id then
    -- A retry, or the reconciler finding a hold somebody else already finished. Nothing to do.
    redis.call('ZREM', checkout_key, hold_id)
    return cjson.encode({ status = 'ALREADY_SOLD', holdId = hold_id, orderId = sold_to })
  end
  return cjson.encode({ status = 'WRONG_STATE', holdStatus = status, orderId = sold_to })
end

if status ~= 'CHECKOUT' or sold_to ~= order_id then
  -- Not frozen, or frozen for somebody else. Either way these seats are not this order's to sell,
  -- and the Postgres guard will already have refused the row that got here.
  return cjson.encode({ status = 'WRONG_STATE', holdStatus = status, orderId = sold_to })
end

local seat_ids = {}
for seat_id in string.gmatch(seats_csv, '[^,]+') do
  seat_ids[#seat_ids + 1] = seat_id
end

-- Only the seats this hold still owns are sold. A seat whose value names another hold was taken
-- over, which the state machine does not allow, and selling it would sell somebody else's seat.
local sold = 0
local held_value = 'H:' .. hold_id
for i = 1, #seat_ids do
  if redis.call('HGET', seats_key, seat_ids[i]) == held_value then
    redis.call('HSET', seats_key, seat_ids[i], 'S')
    sold = sold + 1
  end
end

-- The available counter is deliberately not touched. It was decremented when the seats were held
-- and they never became available again: a sale moves a seat from held to sold, and only a release
-- gives one back.

redis.call('HSET', hold_key, 'status', 'CONSUMED')
redis.call('ZREM', checkout_key, hold_id)
-- Only if it still points at this hold: the user may already hold something else.
if redis.call('GET', userhold_key) == hold_id then
  redis.call('DEL', userhold_key)
end

local version = redis.call('INCR', version_key)
redis.call('XADD', stream_key, 'MAXLEN', '~', stream_maxlen, '*',
  'type', 'SeatsSold',
  'eventId', event_id,
  'holdId', hold_id,
  'seats', seats_csv,
  'version', version,
  'occurredAt', now)

return cjson.encode({
  status = 'OK',
  holdId = hold_id,
  orderId = order_id,
  version = version,
  soldSeats = sold
})
