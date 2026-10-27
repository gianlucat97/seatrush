-- Puts one user in the lobby or in the queue of one event, once (README sections 6.2 and 9.2).
--
-- Joining is idempotent, which is not politeness but a requirement: a client retrying after a
-- timeout, a second browser tab and a refreshed page all arrive here, and every one of them must
-- find the place the user already has. A second sequence number would move that user backwards,
-- which is the one thing a queue may never do.
--
-- Redis runs a script to completion before anything else, so the read of the existing place and
-- the write of a new one are one step. Two pods taking the same user's two clicks at the same
-- millisecond are serialised here, and exactly one of them allocates the sequence.
--
-- Everything this script touches belongs to one event and shares its hash tag, so the whole script
-- runs on one slot and stays valid on a Redis Cluster.
--
-- KEYS[1] lobby set            users waiting for a sale that has not opened
-- KEYS[2] queue sorted set     userId scored by sequence number
-- KEYS[3] sequence counter     strictly increasing, the arrival order of the sale
-- KEYS[4] admission head       highest sequence let through so far
-- KEYS[5] user sequence        this user's sequence, which is also their membership record
--
-- ARGV[1] userId
-- ARGV[2] '1' if the sale has not opened yet, so this arrival waits in the lobby
-- ARGV[3] user sequence ttl    seconds; a safety net so one key per user per event is not
--                             kept for ever once a sale is long over
--
-- Returns a JSON object whose status is LOBBY or SEQUENCED. A SEQUENCED reply carries the
-- sequence and the head, which is all the service needs to say whether the user is still
-- waiting and how many are in front.

local lobby_key = KEYS[1]
local queue_key = KEYS[2]
local sequence_key = KEYS[3]
local head_key = KEYS[4]
local user_key = KEYS[5]

local user_id = ARGV[1]
local into_lobby = ARGV[2] == '1'
local user_ttl = tonumber(ARGV[3])

local function head()
  return tonumber(redis.call('GET', head_key)) or 0
end

-- Already holding a number: that place, whatever was asked for. A user who joined the lobby
-- this morning and asks again now that the doors are open is not a new arrival.
local existing = redis.call('GET', user_key)
if existing then
  return cjson.encode({
    status = 'SEQUENCED',
    sequence = tonumber(existing),
    head = head()
  })
end

if into_lobby then
  -- A set and not a list: the order of arrival inside the lobby is deliberately not recorded,
  -- because it is about to be shuffled away and recording it would invite somebody to rely on it.
  redis.call('SADD', lobby_key, user_id)
  return cjson.encode({ status = 'LOBBY' })
end

-- The sale is live, so this arrival goes to the back of the queue in arrival order. The SREM
-- covers the user who was in the lobby when the doors opened and clicked before the opener
-- reached them: they are sequenced here, and the opener then finds nothing to claim.
redis.call('SREM', lobby_key, user_id)

local sequence = redis.call('INCR', sequence_key)
redis.call('ZADD', queue_key, sequence, user_id)
redis.call('SET', user_key, sequence, 'EX', user_ttl)

return cjson.encode({
  status = 'SEQUENCED',
  sequence = sequence,
  head = head()
})
