package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    
    @Override
    public Result sendCode(String phone, HttpSession session) {
        if (phone == null || RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        String code = RandomUtil.randomNumbers(6);
        // 将验证码保存到session
        session.setAttribute("code", code);
        session.setAttribute("phone", phone);
        log.debug("验证码已发送：{}", code);
        return Result.ok(code);
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        String code = loginForm.getCode();
        // 校验手机号
        Object phoneInSession = session.getAttribute("phone");
        if (phoneInSession == null || !phoneInSession.equals(phone)) {
            return Result.fail("手机号错误");
        }
        // 校验验证码
        Object codeInSession = session.getAttribute("code");
        if (codeInSession == null || !codeInSession.equals(code)) {
            return Result.fail("验证码错误");
        }
        // 如果验证通过，查询用户信息
        User user = query().eq("phone", phone).one();
        if (user == null) {
            user = registerUser(phone);
        }
        // 将用户信息存入session
        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        session.removeAttribute("phone");
        session.removeAttribute("code");
        return Result.ok();
    }

    User registerUser(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + phone);
        save(user);
        return user;
    }
}
