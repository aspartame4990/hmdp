-- KEYS[1] = 锁key，ARGV[1] = 期望的持有者值
if (redis.call('get', KEYS[1]) == ARGV[1]) then
    return redis.call('del', KEYS[1])
else
    return 0
end