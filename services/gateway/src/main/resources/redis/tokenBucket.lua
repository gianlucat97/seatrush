-- One token bucket, refilled by the clock and spent by a request (README sections 6.1 and 13).
--
-- A bucket holds `burst` tokens and regains `per_second` of them every second. A request takes
-- one. A caller who has been quiet has a full bucket and is never delayed; a caller who has been
-- hammering has an empty one and is refused until the clock has put a token back. That shape is
-- the reason a token bucket is the right instrument for an on-sale rather than a fixed window: a
-- buyer clicking three times in a second is normal and must not be punished for it, while the same
-- buyer clicking three times a second for a minute is not, and a fixed window cannot tell those
-- apart without also refusing the first click of every window.
--
-- In Redis and in Lua rather than in the gateway, for the same reason the holds are: the limit has
-- to be the limit of the deployment and not of a pod. Six gateway pods each counting to ten is a
-- limit of sixty, and the autoscaler of README section 24.3 makes that number change under load.
-- Redis runs a script to completion before anything else, so the read of the bucket and the write
-- of what is left of it are one step, and two pods taking the same caller's two clicks in the same
-- millisecond are serialised here.
--
-- One key, so nothing about this script needs a hash tag to stay correct on a Redis Cluster: the
-- bucket of one caller in one scope is the only thing it touches.
--
-- KEYS[1] bucket    hash of `tokens` (what is left, fractional) and `updated` (epoch millis)
--
-- ARGV[1] burst       the size of the bucket, which is how much may arrive at once
-- ARGV[2] per_second  tokens put back each second
-- ARGV[3] now         epoch millis, from the injected clock
--
-- Returns { allowed, retry_after_millis }, two integers because that is all a filter needs and a
-- JSON document per request on this path would be parsed hundreds of thousands of times a sale.
-- `retry_after_millis` is 0 on an allowed request and otherwise the wait until one token exists,
-- which is what the client is told in `Retry-After`.

local bucket_key = KEYS[1]

local burst = tonumber(ARGV[1])
local per_second = tonumber(ARGV[2])
local now = tonumber(ARGV[3])

local state = redis.call('HMGET', bucket_key, 'tokens', 'updated')
local tokens = tonumber(state[1])
local updated = tonumber(state[2])

-- A caller nobody has seen starts full. The alternative, starting empty, would refuse the first
-- request every caller ever makes, which is every buyer of every sale.
if tokens == nil or updated == nil then
  tokens = burst
  updated = now
end

-- Refilled lazily, which is why there is no sweeper for this and no job: a bucket is only ever
-- wrong between two requests of the same caller, and nobody is looking at it then. The clock is
-- passed in rather than read from Redis TIME so that a test can move it, and so that every pod
-- agrees with the one the tokens of README section 22 are measured against.
local elapsed = now - updated
if elapsed > 0 then
  tokens = math.min(burst, tokens + (elapsed / 1000.0) * per_second)
  updated = now
end

local allowed = 0
local retry_after = 0

if tokens >= 1 then
  tokens = tokens - 1
  allowed = 1
else
  -- What the caller is told to wait: the time for the bucket to hold one whole token. Rounded up,
  -- because a client that came back a millisecond early would be refused again and would have been
  -- told a number that was not true.
  retry_after = math.ceil(((1 - tokens) / per_second) * 1000)
end

redis.call('HSET', bucket_key, 'tokens', tokens, 'updated', updated)

-- An idle bucket disappears instead of being swept. The lifetime is how long a full refill takes
-- plus a second, so a key is only kept while forgetting it would change an answer: a bucket that
-- has had time to refill completely is indistinguishable from one that never existed, and this is
-- what keeps a flash on-sale from leaving two hundred thousand bucket keys behind it.
redis.call('PEXPIRE', bucket_key, math.ceil((burst / per_second) * 1000) + 1000)

return { allowed, retry_after }
