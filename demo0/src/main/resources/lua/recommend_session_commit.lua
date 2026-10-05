-- 只有当前构页 owner 才能原子提交完整会话快照并刷新闲置 TTL。
-- 返回 1=提交，0=owner 不匹配，-1=会话不存在或已过期。
if redis.call('GET', KEYS[2]) ~= ARGV[1] then
    return 0
end
if redis.call('EXISTS', KEYS[1]) == 0 then
    return -1
end

local now = tonumber(ARGV[3])
local absoluteExpiresAt = tonumber(ARGV[4])
local idleTtl = tonumber(ARGV[5])
local ttl = math.min(idleTtl, absoluteExpiresAt - now)
if ttl <= 0 then
    return -1
end

redis.call('SET', KEYS[1], ARGV[2], 'XX', 'PX', ttl)
redis.call('ZADD', KEYS[3], ARGV[6], ARGV[7])
return 1
