package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;
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
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    private IUserService userService;

    @Override
    public Result isFollow(Long followUserId) {
        //获取登陆用户
        Long id = UserHolder.getUser().getId();
        //查询是否关注
        Long num = query().eq("user_id", id).eq("follow_user_id", followUserId).count();
        //判断
        return Result.ok(num > 0);
    }

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        //获取登陆用户
        Long id = UserHolder.getUser().getId();
        //判断是关注还是取关
        if (isFollow) {
            //关注 新增数据
            Follow follow = new Follow();
            follow.setFollowUserId(followUserId);
            follow.setUserId(id);
            boolean isSuccess = save(follow);
            if (isSuccess) {
                //把关注用户id放入redis的set集合 sadd key value
                redisTemplate.opsForSet().add("follows:" + id, followUserId.toString());
            }

        } else {
            //取关 删除
            boolean isSuccess = remove(new LambdaQueryWrapper<Follow>()
                    .eq(Follow::getUserId, id)
                    .eq(Follow::getFollowUserId, followUserId)
            );
            if (isSuccess) {
                //把关注用户id从redis的set集合中移除 srem key value
                redisTemplate.opsForSet().remove("follows:" + id, followUserId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result followCommons(Long id) {
        //获取登陆用户
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        //求交集
        String key2 = "follows:" + id;
        Set<String> intersect = redisTemplate.opsForSet().intersect(key, key2);
        if (intersect == null || intersect.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        //解析出id
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        //查询用户
        List<User> users = userService.listByIds(ids);
        List<UserDTO> collect = users.stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(collect);
    }
}
