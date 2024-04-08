package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

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
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            //2.不符合就直接返回错误信息
            return Result.fail("手机号格式错误!");
        }
        //3.符合就生成验证码
        String code = RandomUtil.randomNumbers(6);
        //4.将验证码保存到session
        session.setAttribute("code",code);
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
        //2.校验验证码
        Object cacheCode = session.getAttribute("code");
        String code=loginForm.getCode();
        if(cacheCode==null||!cacheCode.toString().equals(code)){
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
        //7.将用户信息保存到session中
        session.setAttribute("user",user);
        return null;
    }
    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));
        save(user);//保存用户
        return user;
    }
}
