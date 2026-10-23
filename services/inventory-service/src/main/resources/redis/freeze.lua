-- Freezes an active hold into a checkout (README sections 8.3 and 9.2).
--
-- This is the step that hands a hold over from its user to the checkout SAGA. Two things change
-- and nothing else: the status becomes CHECKOUT and the hold leaves the expiry set, so the sweeper
-- of README section 9.3 stops looking at it. From here the SAGA owns the hold and ends it with
-- either a commit or a release, which is why its countdown has to stop in the same step that takes
-- it over: a sweeper that expired a hold being paid for would take the seats off a live order.
--
-- The seat state is deliberately untouched. The seats were already H:<holdId> before this ran and
-- they still are, so the seat map does not change, there is no version to hand out and there is no
-- seat event to relay. A freeze is invisible to everybody watching the map, which is correct: the
-- seats were unavailable before and are unavailable after.
--
-- Idempotent for the same order, because the order-service may retry its call: a hold already in
-- CHECKOUT for this orderId answers OK with the same seats instead of refusing. A hold in CHECKOUT
-- for a different order is the one case that must fail, since two orders cannot both own the seats.
--
-- KEYS[1] expiry sorted set    ACTIVE holdId scored by expiresAt
-- KEYS[2] hold hash            the hold itself
--
-- ARGV[1] holdId
-- ARGV[2] orderId              the order taking the hold over
-- ARGV[3] userId               the owner the caller claims, or an empty string to skip the check
-- ARGV[4] now                  epoch millis, from the injected clock
--
-- Returns a JSON object whose status is OK, NOT_FOUND, FORBIDDEN, ALREADY_FROZEN, EXPIRED or
-- WRONG_STATE.

local expiry_key = KEYS[1]
local hold_key = KEYS[2]

local hold_id = ARGV[1]
local order_id = ARGV[2]
local caller_id = ARGV[3]
local now = tonumber(ARGV[4])

local stored = redis.call('HMGET', hold_key, 'userId', 'seats', 'expiresAt', 'status', 'orderId')
local owner_id = stored[1]
local seats_csv = stored[2]
local expires_at = tonumber(stored[3])
local status = stored[4]
local frozen_for = stored[5]

if not owner_id then
  return cjson.encode({ status = 'NOT_FOUND' })
end
if caller_id ~= '' and owner_id ~= caller_id then
  return cjson.encode({ status = 'FORBIDDEN' })
end

local seat_ids = {}
for seat_id in string.gmatch(seats_csv, '[^,]+') do
  seat_ids[#seat_ids + 1] = seat_id
end

if status == 'CHECKOUT' then
  if frozen_for == order_id then
    -- A retry of the call that already succeeded. The expiry entry is gone and the status is
    -- already what this call wanted, so there is nothing left to do but answer again.
    return cjson.encode({
      status = 'OK',
      holdId = hold_id,
      orderId = order_id,
      seatIds = seat_ids,
      expiresAt = expires_at
    })
  end
  return cjson.encode({ status = 'ALREADY_FROZEN', orderId = frozen_for })
end

if status ~= 'ACTIVE' then
  -- CONSUMED, RELEASED or EXPIRED: the hold is finished and no order can take it over.
  return cjson.encode({ status = 'WRONG_STATE', holdStatus = status })
end

-- Still ACTIVE, but out of time. The sweeper has not reached it yet, and freezing it anyway would
-- hand an order seats whose countdown had already run out, which is a race the user would lose
-- without ever being told. The hold stays in the expiry set, so the next sweep frees the seats.
if expires_at <= now then
  return cjson.encode({ status = 'EXPIRED', expiresAt = expires_at })
end

redis.call('HSET', hold_key, 'status', 'CHECKOUT', 'orderId', order_id)
redis.call('ZREM', expiry_key, hold_id)

return cjson.encode({
  status = 'OK',
  holdId = hold_id,
  orderId = order_id,
  seatIds = seat_ids,
  expiresAt = expires_at
})
