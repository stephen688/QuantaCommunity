-- 仅删除匹配 owner 的构页锁，避免旧 owner 清掉新 owner 的租约。
if redis.call('GET', KEYS[1]) == ARGV[1] then
    return redis.call('DEL', KEYS[1])
end
return 0
