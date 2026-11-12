-- Where one user stands in one sale, read in a single step (README section 12.3).
--
-- A script for a read that changes nothing, which needs a reason. The reason is that the answer is
-- made of four facts that have to agree with each other: a sequence, the head it is measured
-- against, whether a live slot exists, and failing all of those whether the user is in the lobby.
-- Read one at a time they can disagree, and the disagreement is the one that matters: a user seen
-- as admitted by the head and then seen without a slot a millisecond later would be told they are
-- in the purchase area and handed no token to be there with.
--
-- The other reason is cost. Every waiting client polls this, so a flash on-sale makes it the
-- busiest read in the system; one round trip rather than three is the difference that shows up.
--
-- KEYS[1] lobby set
-- KEYS[2] admission head
-- KEYS[3] user sequence
-- KEYS[4] active sorted set
--
-- ARGV[1] userId
-- ARGV[2] now                  epoch millis, from the injected clock
--
-- Returns a JSON object whose status is NONE, LOBBY or SEQUENCED. A SEQUENCED reply carries the
-- sequence, the head, and admittedUntil when the user holds a slot that has not expired.

local lobby_key = KEYS[1]
local head_key = KEYS[2]
local user_key = KEYS[3]
local active_key = KEYS[4]

local user_id = ARGV[1]
local now = tonumber(ARGV[2])

local sequence = redis.call('GET', user_key)
if not sequence then
  if redis.call('SISMEMBER', lobby_key, user_id) == 1 then
    return cjson.encode({ status = 'LOBBY' })
  end
  return cjson.encode({ status = 'NONE' })
end

local reply = {
  status = 'SEQUENCED',
  sequence = tonumber(sequence),
  head = tonumber(redis.call('GET', head_key)) or 0
}

-- A slot that expired is not a slot. The admission job removes it on its next pass; until then it
-- is simply not reported, so a lapsed buyer is never handed a token for a slot that is gone.
local admitted_until = tonumber(redis.call('ZSCORE', active_key, user_id))
if admitted_until and admitted_until > now then
  reply.admittedUntil = admitted_until
end

return cjson.encode(reply)
