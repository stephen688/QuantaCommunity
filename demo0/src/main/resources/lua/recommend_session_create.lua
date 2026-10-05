-- 原子创建推荐会话：同一主体/会话 ID 幂等，活跃会话限额与索引清理在同一脚本内完成。
-- 返回 1=创建成功，2=已有活跃会话，0=达到活跃上限，-1=已过期墓碑阻止复活。
local sessionKey = KEYS[1]
local activeKey = KEYS[2]
local tombstoneKey = KEYS[3]
local now = tonumber(ARGV[1])
local activeExpiresAt = tonumber(ARGV[2])
local maxActive = tonumber(ARGV[3])
local payload = ARGV[4]
local sessionId = ARGV[5]
local sessionTtl = tonumber(ARGV[6])
local tombstoneTtl = tonumber(ARGV[7])
local activeTtl = tonumber(ARGV[8])

if redis.call('EXISTS', sessionKey) == 1 then
    return 2
end
if redis.call('EXISTS', tombstoneKey) == 1 then
    return -1
end

redis.call('ZREMRANGEBYSCORE', activeKey, '-inf', now)
if redis.call('ZCARD', activeKey) >= maxActive then
    return 0
end

local created = redis.call('SET', sessionKey, payload, 'NX', 'PX', sessionTtl)
if not created then
    return 2
end

redis.call('ZADD', activeKey, activeExpiresAt, sessionId)
redis.call('PEXPIRE', activeKey, activeTtl)
redis.call('SET', tombstoneKey, '1', 'NX', 'PX', tombstoneTtl)
return 1
