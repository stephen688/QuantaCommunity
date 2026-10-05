-- 仅凭匹配的服务器 owner 删除短暂幂等占位，避免旧请求删除新请求的占位。
if redis.call('get', KEYS[1]) == ARGV[1] then
    return redis.call('del', KEYS[1])
end
return 0
