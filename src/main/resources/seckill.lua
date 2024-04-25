-- 1.参数列表
-- 1.1优惠券id
local voucherId = ARGV[1]
-- 1.2用户id
local userId = ARGV[2]

-- 2.数据key
-- 2.1 库存key
local stockKey = 'seckill:stock:' .. voucherId
-- 2.2 订单key
local orderKey = 'seckill:order:' .. voucherId

-- 3.业务代码
-- 3.1 判断库存是否充足
if stockKey == false or tonumber(redis.call('get', stockKey)) <= 0 then
    -- 库存不足就返回1
    return 1
end
-- 3.2 判断用户是否下单【即集合中是否存在该用户】
if redis.call('sismember', orderKey, userId) == 1 then
    -- 存在该用户，说明该用户重复下单
    return 2
end
-- 3.3 扣减库存
redis.call('incrby', stockKey, -1)
-- 3.4 下单
redis.call('sadd',orderKey,userId)
-- 成功就返回0
return 0