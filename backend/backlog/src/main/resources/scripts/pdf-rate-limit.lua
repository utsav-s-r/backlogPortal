-- Fixed-window counter for PdfRateLimitService. KEYS[1] = bucket, ARGV[1] = window in ms.
-- One script = atomic: INCR and the TTL land together or not at all. As two round trips, an EXPIRE
-- failing after INCR=1 leaves a TTL-less key that locks the user out for good.
-- PTTL -1 (exists, no TTL) gets a TTL on any hit, not just the first, so a stranded key heals on its
-- next hit; a live TTL is never re-armed, so a burst can't turn the fixed window into a rolling one.
local count = redis.call('INCR', KEYS[1])
if redis.call('PTTL', KEYS[1]) == -1 then
  redis.call('PEXPIRE', KEYS[1], ARGV[1])
end
return count
