-- Appends a batch of lobby members to the queue, in the order they are given (README section 6.2).
--
-- This is the moment the waiting room is fair or is not. Everybody who joined before the doors
-- opened is in a set, with no order of their own, and the service shuffles them before calling
-- this: arriving at midnight and arriving a minute before it are then worth the same, and the
-- stampede of refreshes at the announced time buys nobody anything.
--
-- The shuffle is not done here on purpose. Redis replaces math.random with a deterministic
-- generator seeded identically for every run of a script, so that a replica replaying it reaches
-- the same state; a shuffle built on that is a fixed permutation with extra steps, which would be
-- worse than no shuffle because it would look fair. The order therefore arrives in ARGV.
--
-- Removing the user from the lobby is the claim, and it is what makes this safe on every pod at
-- once with no leader and no lock: SREM answers 1 to exactly one caller, so exactly one caller
-- allocates a sequence. The others find 0 and skip, which is also what makes a redelivered batch
-- a no-op rather than a second place in the queue.
--
-- KEYS[1] lobby set
-- KEYS[2] queue sorted set
-- KEYS[3] sequence counter
-- KEYS[4..] the user sequence key of each user, in the same order as the ids in ARGV. Passed in
--           rather than built from the ids, so every key this script touches is declared and
--           Redis Cluster can route it; they all share the event's hash tag, so it is one slot.
--
-- ARGV[1] user sequence ttl    seconds
-- ARGV[2..] userIds            the shuffled order to append them in
--
-- Returns how many users this call actually moved into the queue.

local lobby_key = KEYS[1]
local queue_key = KEYS[2]
local sequence_key = KEYS[3]

local user_ttl = tonumber(ARGV[1])

local queued = 0
for i = 2, #ARGV do
  local user_id = ARGV[i]
  local user_key = KEYS[i + 2]

  if redis.call('SREM', lobby_key, user_id) == 1 then
    -- Claimed. A user who somehow already holds a sequence keeps it: giving them a second one
    -- would move them backwards, and the lobby entry was the stale half of the pair.
    if redis.call('EXISTS', user_key) == 0 then
      local sequence = redis.call('INCR', sequence_key)
      redis.call('ZADD', queue_key, sequence, user_id)
      redis.call('SET', user_key, sequence, 'EX', user_ttl)
      queued = queued + 1
    end
  end
end

return queued
