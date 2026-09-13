local current = redis.call('INCR', KEYS[1])

if current == 1 then
    redis.call('EXPIRE', KEYS[1], ARGV[1])
end

local ttl = redis.call('TTL', KEYS[1])
local limit = tonumber(ARGV[2])
local allowed = 0

if current <= limit then
    allowed = 1
end

local remaining = limit - current
if remaining < 0 then
    remaining = 0
end

return {allowed, remaining, ttl}
