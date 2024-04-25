package com.hmdp.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    @Resource
    StringRedisTemplate stringRedisTemplate;
    private static final long BEGIN_TIMESTAMP = 1712707200L;//生成的业务开始时间戳
    private static final int COUNT_BITS = 32;//序列号位数

    public long nextId(String keyPrefix) {
        //1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;
        //2.生成序列号
        //2.1获取当前日期【精确到天】，自增长id的键需要拼接上时间【避免超过序列号存储上限且方便统计】
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        //2.2自增长【这里使用基本数据类型方便计算，并且不存在空指针的情况，因为如果key不存在也会自动生成一个值为0的key】
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);
        //3.拼接后返回【先向左移把32位空出来，然后同count进行或运算，只要有1就为真】
        return timestamp << COUNT_BITS | count;
    }

    public static void main(String[] args) {
        LocalDateTime localDateTime = LocalDateTime.of(2024, 4, 10, 0, 0, 0);
        System.out.println(localDateTime.toEpochSecond(ZoneOffset.UTC));
    }
}
