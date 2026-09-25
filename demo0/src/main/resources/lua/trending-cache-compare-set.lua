local current = redis.call('GET', KEYS[1])
if ARGV[1] == 'ABSENT' then
    if current then
        return 0
    end
elseif current ~= ARGV[2] then
    return 0
end

redis.call('SET', KEYS[1], ARGV[3], 'EX', ARGV[4])
return 1
