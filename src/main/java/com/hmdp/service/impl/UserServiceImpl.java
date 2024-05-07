package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            //2.不符合就直接返回错误信息
            return Result.fail("手机号格式错误!");
        }
        //3.符合就生成验证码
        String code = RandomUtil.randomNumbers(6);
        //4.将验证码保存到redis，并且设置过期时间
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //5.发送验证码，这一块是伪实现，如果要实现发送验证码功能可以去阿里云找短信验证的api接口
        log.debug("发送成功,验证码为:{}",code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误!");
        }
        //2.从redis中获取验证码并且验证
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code=loginForm.getCode();
        if(cacheCode==null||!cacheCode.equals(code)){
            //3.不一致,报错
            return  Result.fail("验证码错误");
        }
        //4.一致，根据手机号查询用户
        User user = query().eq("phone", phone).one();
        //5.判断用户是否存在
        if(user==null){
            //6.不存在就创建一个新用户
            user=createUserWithPhone(phone);
        }
        //7.将用户信息保存到session中，创建UserDTO类型的对象，并且拷贝user同名属性
        //7.1生成登录令牌
        String token = UUID.randomUUID().toString(true);
        //7.2将User对象转成Hash存储【我这边还是推荐存储成string，然后设置序列化机制】
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);//隐藏部分字段
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
               CopyOptions.create()
                       .setIgnoreNullValue(true)//设置忽略null值
                       .setFieldValueEditor(
                       (fieldName,fieldValue)//接收字段名和字段值
                               -> fieldValue.toString()));//将字段值转成string类型
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);
        //7.3设置令牌有效期
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.MINUTES);
        //8.返回登录令牌
        return Result.ok(token);
    }

    @Override
    public Result sign() {
        //1.获取当前登录的用户
        Long userId = UserHolder.getUser().getId();
        //2.获取日期
        LocalDateTime now = LocalDateTime.now();
        //3.拼接key
        String dateKey = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String userSignKey=USER_SIGN_KEY+userId+dateKey;
        //4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //5.记录该用户今天的签到
        stringRedisTemplate.opsForValue().setBit(userSignKey,dayOfMonth-1,true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        //1.获取当前用户本月截止到今天的所有签到记录
        Long userId = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();
        String dateKey = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String userSignKey=USER_SIGN_KEY+userId+dateKey;
        int dayOfMonth = now.getDayOfMonth();
        //由于bitField可以同时进行get、set等多种操作，所以返回的结果是一个集合
        List<Long> bitField = stringRedisTemplate.opsForValue().bitField(userSignKey, BitFieldSubCommands.create().
                //将0-dayOfMonth的所有比特位以十进制无符号形式返回
                        get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
        //2.位域获取到的是十进制数,需要每次用1和当前数字进行与运算得到当前最低位
        if(bitField==null||bitField.isEmpty()){
            //没有签到结果
            return Result.ok(0);
        }
        Long num = bitField.get(0);
        if(num==null||num==0){
            //num为0说明0-dayOfMonth的所有比特位都是0
            return Result.ok(0);
        }
        int count=0;
        //3.循环判断每个最低位是否为1
        while((num&1)==1){
            count++;
            num>>>=1;//无符号右移，判断前一天是否签到了
        }
        return Result.ok(count);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));
        save(user);//保存用户
        return user;
    }
}
