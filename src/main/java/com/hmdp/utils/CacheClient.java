package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {
    @Resource
    StringRedisTemplate stringRedisTemplate;
    /**
     *设置过期时间
     */
    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }
    /**
     *设置逻辑过期时间
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        //封装逻辑过期的字段
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //将redisData写入redis，并且不设置过期时间
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }
    public <R,IDType> R queryWithPassThrough(String keyPrefix, IDType id, Class<R> type, Function<IDType,R> dbFallBack
    ,Long time, TimeUnit unit){
        //1.从redis查询商铺缓存
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断商铺信息是否存在
        if(StrUtil.isNotBlank(json)){
            //3.存在就直接返回缓存数据
            return JSONUtil.toBean(json, type);//转化成指定实体类
        }
        //如果命中空值，说明是无效数据，直接返回不存在,就不需要去数据库查询了
        if("".equals(json)){
            return null;
        }
        //4.不存在就根据id去数据库中查
        R data = dbFallBack.apply(id);//这一块工具类不知道具体要去查哪个数据库，只能交给调用者去处理，所以使用函数式编程
        //5.如果数据库不存在就返回错误信息
        if(data==null){
            //数据不存在就缓存空值
            this.set(key,"",time,unit);
            return null;
        }
        //6.存在就写回到redis
        this.set(key,data,time,unit);
        return data;
    }
    private static final ExecutorService threadPool= Executors.newFixedThreadPool(10);
    //基于逻辑过期时间解决缓存击穿
    public <R,IDType> R queryWithLogicalExpire(String keyPrefix, IDType id, Class<R> type, Function<IDType,R> dbFallBack
            ,Long time, TimeUnit unit)  {
        //1.从redis查询商铺缓存
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断商铺信息是否存在
        if(StrUtil.isBlank(json)){
            //3.不存在就根据id去数据库中查
            R data = dbFallBack.apply(id);//这一块工具类不知道具体要去查哪个数据库，只能交给调用者去处理，所以使用函数式编程
            if(data==null){
                //如果数据库不存在就返回错误信息
                return null;
            }else {
                this.setWithLogicalExpire(key,data,time,unit);
                return data;
            }
        }
        //4.缓存命中，就需要先把json反序列化位对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        //将Object类型数据转换成Shop类型
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.判断是否过期，即过期时间是否在当前时间之后，在当前时间之前就没过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //5.1未过期就直接返回店铺信息
            return r;
        }
        //5.2过期就需要缓存重建
        String lockKey=LOCK_SHOP_KEY+id;
        boolean isLock = tryLock(lockKey);
        if(isLock){
            //获取锁成功就开启异步线程完成缓存重建
            threadPool.submit(()->{
                try {
                    R r1 = dbFallBack.apply(id);
                    //写入redis
                    this.setWithLogicalExpire(key,r1,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    unlock(lockKey);//释放锁
                }
            });
        }
        return r;//先返回旧数据挡一下，然后由异步线程来修改
    }
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);//该工具类会自动拆箱，并且防止空指针异常
    }
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }
    //TODO 基于布隆过滤器防止缓存穿透
}
