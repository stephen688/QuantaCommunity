-- 记录推荐真实可视曝光：清理过期成员，再以 NX 写入同批 ID。
-- 返回本次真正新增的成员数量；重复上报不会刷新已有 member 的失效时间。
local now = tonumber(ARGV[1])
local expiresAt = tonumber(ARGV[2])
local idleTtl = tonumber(ARGV[3])
local inserted = 0

redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)
for index = 4, #ARGV do
    inserted = inserted + redis.call('ZADD', KEYS[1], 'NX', expiresAt, ARGV[index])
end

if inserted > 0 then
    local currentTtl = redis.call('PTTL', KEYS[1])
    if currentTtl < idleTtl then
        redis.call('PEXPIRE', KEYS[1], idleTtl)
    end
end

return inserted
