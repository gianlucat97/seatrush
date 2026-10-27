-- Lets the next buyers of one event into the purchase area (README section 6.2).
--
-- Three things in one atomic step, and they have to be one step because the second depends on the
-- first and the third would otherwise oversubscribe the sale:
--
--   1. Slots whose admission has expired are freed. The active set is scored by the instant each
--      admission dies, so that is one range delete and not a scan. This is the slot release of
--      README section 6.2, and doing it here rather than in a sweeper of its own means there is no
--      interval during which the count of active buyers is known to be wrong.
--   2. How many may be let in now is whatever is left of max-active-users, capped by the rate.
--      This is the cap that makes the purchase area a bounded thing: the queue can be two hundred
--      thousand long, and inventory still only ever sees max-active-users buyers at once.
--   3. The lowest sequences are popped and given a slot, and the head is moved to the highest
--      sequence let through. The head is what every waiting client's position is measured against.
--
-- ZPOPMIN is what makes this safe on every pod at once with no leader and no lock: a user is
-- removed from the queue by exactly one caller, so two pods admitting at the same moment share the
-- batch rather than admitting the same person twice.
--
-- Everything this script touches belongs to one event and shares its hash tag, so the whole script
-- runs on one slot and stays valid on a Redis Cluster.
--
-- KEYS[1] queue sorted set     userId scored by sequence number
-- KEYS[2] active sorted set    admitted userId scored by the instant their admission expires
-- KEYS[3] admission head       highest sequence let through so far
--
-- ARGV[1] now                  epoch millis, from the injected clock
-- ARGV[2] expires at           epoch millis; the instant this batch's admissions die, which is
--                              also the instant the tokens for them will carry
-- ARGV[3] rate                 how many may be admitted in this pass
-- ARGV[4] max active users     how many may be inside the purchase area at once
--
-- Returns a JSON object with the new head, how many are now active, how many expired slots this
-- pass freed, and the users admitted. The list of users is left out entirely when nobody was
-- admitted, rather than sent as an empty one: the cjson of Redis encodes an empty Lua table as an
-- object and not as an array, so an empty list on the wire would arrive as {} and fail to read.

local queue_key = KEYS[1]
local active_key = KEYS[2]
local head_key = KEYS[3]

local now = tonumber(ARGV[1])
local expires_at = tonumber(ARGV[2])
local rate = tonumber(ARGV[3])
local max_active = tonumber(ARGV[4])

local head = tonumber(redis.call('GET', head_key)) or 0

-- Inclusive of now: an admission expiring exactly now is expired, which is the same comparison
-- the token verifier makes. The two have to agree, or a buyer would hold a token for a slot that
-- is gone or a slot for a token that is dead.
local freed = redis.call('ZREMRANGEBYSCORE', active_key, '-inf', now)

local active = redis.call('ZCARD', active_key)
local room = max_active - active
if room > rate then
  room = rate
end
if room <= 0 then
  return cjson.encode({
    head = head,
    activeUsers = active,
    freedSlots = freed
  })
end

local popped = redis.call('ZPOPMIN', queue_key, room)

local admitted = {}
-- ZPOPMIN answers a flat array of member, score, member, score.
for i = 1, #popped, 2 do
  local user_id = popped[i]
  local sequence = tonumber(popped[i + 1])

  redis.call('ZADD', active_key, expires_at, user_id)
  admitted[#admitted + 1] = user_id
  if sequence > head then
    head = sequence
  end
end

if #admitted > 0 then
  -- The head only ever moves forward, because the sequences popped are the lowest left and this
  -- script is the only thing that writes it. A position measured against it therefore never rises.
  redis.call('SET', head_key, head)
end

local reply = {
  head = head,
  activeUsers = active + #admitted,
  freedSlots = freed
}
if #admitted > 0 then
  reply.admitted = admitted
end

return cjson.encode(reply)
