package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
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
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query().orderByDesc("liked").page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(this::queryBlogUser);
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在");
        }
        queryBlogUser(blog);
        return Result.ok(blog);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
        isBlogLikedByUser(blog);
    }

    @Override
    public Result likeBlog(Long id) {
        Long userId = UserHolder.getUser().getId();
        // 1.判断当前用户是否已经点赞
        String key = "blog:liked:" + id;
        Boolean isMember = redisTemplate.opsForZSet().score(key, userId.toString()) != null;
        // 2.如果未点赞，可以点赞
        if (BooleanUtil.isFalse(isMember)) {
            // 数据库点赞数 +1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            if (isSuccess) {
                // 保存用户到Redis的Set集合
                redisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
            return Result.ok();
        }
        // 3.如果已经点赞，取消点赞
        else {
            // 数据库点赞数 -1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            if (isSuccess) {
                // 把用户从Redis的Set集合移除
                redisTemplate.opsForZSet().remove(key, userId.toString());
            }
            return Result.ok();
        }
    }

    private void isBlogLikedByUser(Blog blog) {
        if (UserHolder.getUser() == null) {
            // 用户未登录
            blog.setIsLike(false);
            return;
        }
        String key = "blog:liked:" + blog.getId();
        boolean isLiked = BooleanUtil.isTrue(redisTemplate.opsForZSet().score(key, UserHolder.getUser().getId().toString()) != null);
        blog.setIsLike(isLiked);
    }

    @Override
    public Result likesBy(Long id) {
        // 1.查询top5的点赞用户
        String key = "blog:liked:" + id;
        Set<String> top5 = redisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok();
        }
        // 2.解析出其中的用户id
        List<Long> userIds = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idstr = StrUtil.join(",", userIds);
        List<UserDTO> users = userService.query().in("id", userIds).last("ORDER BY FIELD(id," + idstr + ")").list()
                .stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(users);
    }
}
