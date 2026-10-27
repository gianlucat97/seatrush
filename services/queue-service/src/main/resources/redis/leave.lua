-- Takes one user out of the lobby and out of the queue of one event (README section 12.3).
--
-- All three places in one step, because a user who is half gone is worse than one who never
-- left: a sequence key without a queue entry is a user the admission job will never reach, and a
-- queue entry without a sequence key is a user who cannot be told where they stand.
--
-- The admission head is deliberately not touched. It counts how far the door has got, not how
-- many people are left, so somebody leaving does not pull the queue forward; it makes the
-- position of everybody behind them slightly pessimistic, which README section 6.2 accepts in
-- exchange for an O(1) position.
--
-- KEYS[1] lobby set
-- KEYS[2] queue sorted set
-- KEYS[3] user sequence
--
-- ARGV[1] userId
--
-- Returns 1 if the user was waiting anywhere, 0 if there was nothing to remove.

local lobby_key = KEYS[1]
local queue_key = KEYS[2]
local user_key = KEYS[3]

local user_id = ARGV[1]

local removed = redis.call('SREM', lobby_key, user_id)
removed = removed + redis.call('ZREM', queue_key, user_id)
removed = removed + redis.call('DEL', user_key)

if removed > 0 then
  return 1
end
return 0
