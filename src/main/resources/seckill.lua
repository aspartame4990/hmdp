-- 1. 参数列表
local voucherId = ARGV[1] -- 秒杀的优惠券ID
local userId = ARGV[2] -- 用户ID
local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId
-- 2. 判断库存是否充足
local stock = redis.call('get', stockKey)
if not stock or tonumber(stock) <= 0 then
    return 1 -- 库存不足
end
-- 3. 判断用户是否已经下单
if (redis.call('sismember', orderKey, userId) == 1) then
    return 2 -- 用户已经下单
end
-- 4. 扣减库存,创建订单
redis.call('decr', stockKey)
redis.call('sadd', orderKey, userId)
return 0