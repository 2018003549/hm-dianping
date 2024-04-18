package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;
    @Override
    public Result queryShopById(Long id) {
        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById
                , LOCK_SHOP_TTL, TimeUnit.SECONDS);
        if (shop==null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }
    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id==null){
            return Result.fail("店铺id不能为空");
        }
        //1.更新数据库
        updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY+id);
        return Result.ok();
    }
    //基于分布式锁解决缓存击穿
    public Shop queryWithMutex(Long id)  {
        //1.从redis查询商铺缓存
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断商铺信息是否存在
        if(StrUtil.isNotBlank(shopJson)){
            //3.存在就直接返回缓存数据
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);//转化成商铺实体类
            return shop;
        }
        //如果命中空值，说明是无效数据，直接返回不存在,就不需要去数据库查询了
        if("".equals(shopJson)){
            return null;
        }
        //==========4.实现缓存重建===========
        Shop shop = null;
        String lockKey=LOCK_SHOP_KEY+id;
        try {
            //4.1获取互斥锁
            boolean isLock = tryLock(lockKey);
            //4.2判断是否获取成功
            if(!isLock){
                //获取失败就休眠尝试
                Thread.sleep(50);
                return queryWithPassThrough(id);
            }
            shop = getById(id);
            Thread.sleep(200);//模拟重建的延时
            //5.如果数据库不存在就返回错误信息
            if(shop==null){
                //数据不存在就缓存空值
                stringRedisTemplate.opsForValue().set(key,"");
                return null;
            }
            //6.存在就写回到redis
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //7.释放互斥锁
            unlock(lockKey);
        }
        return shop;
    }
    private static final ExecutorService threadPool= Executors.newFixedThreadPool(10);
    //基于逻辑过期时间解决缓存击穿
    public Shop queryWithLogicalExpire(Long id)  {
        //1.从redis查询商铺缓存
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断商铺信息是否存在
        if(StrUtil.isBlank(shopJson)){
            //3.不存在直接返回null
            return null;
        }
        //4.缓存命中，就需要先把json反序列化位对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        //将Object类型数据转换成Shop类型
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.判断是否过期，即过期时间是否在当前时间之后，在当前时间之前就没过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //5.1未过期就直接返回店铺信息
            return shop;
        }
        //5.2过期就需要缓存重建
        //6.缓存重建
        String lockKey=LOCK_SHOP_KEY+id;
        boolean isLock = tryLock(lockKey);
        if(isLock){
            //获取锁成功就开启异步线程完成缓存重建
            threadPool.submit(()->{
                try {
                    this.saveShopRedis(id,20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    unlock(lockKey);//释放锁
                }
            });
        }
        return shop;
    }
    //缓存空值防止缓存穿透
    public Shop queryWithPassThrough(Long id){
        //1.从redis查询商铺缓存
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断商铺信息是否存在
        if(StrUtil.isNotBlank(shopJson)){
            //3.存在就直接返回缓存数据
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);//转化成商铺实体类
            return shop;
        }
        //如果命中空值，说明是无效数据，直接返回不存在,就不需要去数据库查询了
        if("".equals(shopJson)){
            return null;
        }
        //4.不存在就根据id去数据库中查
        Shop shop = getById(id);
        //5.如果数据库不存在就返回错误信息
        if(shop==null){
            //数据不存在就缓存空值
            stringRedisTemplate.opsForValue().set(key,"");
            return null;
        }
        //6.存在就写回到redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);//该工具类会自动拆箱，并且防止空指针异常
    }
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }
    public void saveShopRedis(Long id,Long expireSeconds) throws InterruptedException {
        //1.查询店铺数据
        Shop shop = getById(id);
        Thread.sleep(200);//模拟延迟
        //2.封装过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3.存入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }
}
