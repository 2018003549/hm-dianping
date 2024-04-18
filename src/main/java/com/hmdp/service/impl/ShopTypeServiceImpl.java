package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryTypeList() {
        //1.从redis查询出所有的商铺类别
        List<ShopType> typeList = stringRedisTemplate.opsForList().
                range("typeList", 0, -1).stream().map(type -> {
                    return JSONUtil.toBean(type, ShopType.class);
                }).collect(Collectors.toList());
        //2.判断商铺信息是否存在
        if(typeList!=null&&typeList.size()>0){
            //3.存在就直接返回缓存数据
            return Result.ok(typeList);
        }
        //4.不存在就根据id去数据库中查
        typeList = query().orderByAsc("sort").list();
        //5.如果数据库不存在就返回错误信息
        if(typeList==null&&typeList.size()==0){
            return Result.fail("没有定义商铺类别信息！！！");
        }
        //6.存在就写回到redis
        stringRedisTemplate.opsForList().rightPushAll("typeList",typeList.stream().map(
                shopType -> {
                    return JSONUtil.toJsonStr(shopType);
                }
        ).collect(Collectors.toList()));
        return Result.ok(typeList);
    }
}
