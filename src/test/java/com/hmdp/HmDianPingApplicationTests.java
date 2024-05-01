package com.hmdp;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.service.impl.ShopServiceImpl;

import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.*;

@SpringBootTest
class HmDianPingApplicationTests {
    @Resource
    ShopServiceImpl shopService;
    @Resource
    CacheClient cacheClient;
    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Test
    void testLogicDelete() throws InterruptedException {
        Shop shop = shopService.getById(1l);
        cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY + 1L, shop, 10l, TimeUnit.SECONDS);
    }

    private ExecutorService es = Executors.newFixedThreadPool(500);
    @Resource
    RedisIdWorker redisIdWorker;

    @Test
    void testUniqueId() throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(300);//计数器
        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long order = redisIdWorker.nextId("order");
//                System.out.println(order);
            }
            countDownLatch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        countDownLatch.await();
        long end = System.currentTimeMillis();
        System.out.println("time=" + (end - begin));
    }

    @Resource
    RabbitTemplate rabbitTemplate;

    @Test
    void testSendMessage() {
        rabbitTemplate.convertAndSend("seckill.direct", "seckill.order", "测试发送消息");
    }

    @Test
    void testMultUser() {
        //向redis中存入1000条token信息
        // 文件路径
        String filePath = "tokens.txt";
        // 生成1000个token并存储到Redis和本地文本文件
        for (long i = 0; i < 1000; i++) {
            String token = generateToken();
            UserDTO userDTO = new UserDTO();
            userDTO.setId(i);
            userDTO.setIcon("未知");
            userDTO.setNickName("用户" + i);
            Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                    CopyOptions.create()
                            .setIgnoreNullValue(true)//设置忽略null值
                            .setFieldValueEditor(
                                    (fieldName, fieldValue)//接收字段名和字段值
                                            -> fieldValue.toString()));//将字段值转成string类型
            // 存储到Redis
            stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, userMap);
            // 存储到本地文本文件
            writeToFile(filePath, token);
        }
        System.out.println("Token生成完成！");
    }

    // 生成一个随机token
    private static String generateToken() {
        return UUID.randomUUID().toString();
    }

    // 将token写入本地文件
    private static void writeToFile(String filePath, String token) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath, true))) {
            writer.write(token);
            writer.newLine();
        } catch (IOException e) {
            System.err.println("写入文件时出错：" + e.getMessage());
        }
    }

    @Test
    void loadShopData() {
        //1.查询点评信息
        List<Shop> list = shopService.list();
        //2.按照店铺类型分组
        Map<Long, List<Shop>> map = list.stream().collect(
                Collectors.groupingBy(Shop::getTypeId));//groupingBy可以根据指定参数进行分组
        //3.分批写入Redis
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            //3.1获取店铺类型id
            Long typeId = entry.getKey();
            String key = SHOP_GEO_KEY + typeId;
            //3.2获取同类相的店铺集合
            List<Shop> shops = entry.getValue();
            //3.3写入每个店铺的地理坐标
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>();
            for (Shop shop : shops) {
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(), shop.getY())
                ));
            }
            stringRedisTemplate.opsForGeo().add(key, locations);
        }
    }

    @Test
    void testHyperLogLog() {
        // 准备数组，装用户数据
        String[] users = new String[1000];
        int index = 0;
        for (int i = 1; i <= 1000000; i++) {
            users[index++] = "user_" + i;
            // 每1000条发送一次
            if (i % 1000 == 0) {
                index = 0;
                stringRedisTemplate.opsForHyperLogLog().add("hll1", users);
            }
        }    // 统计数量
        Long size = stringRedisTemplate.opsForHyperLogLog().size("hll1");
        System.out.println("size = " + size);
    }
}
