package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/**
 * redis工具
 *
 * @author CHEN
 * @date 2022/10/08
 */
@Slf4j
@Component
public class CacheClient {

    @Autowired
    private StringRedisTemplate redisTemplate;

    public void set(String key, Object value, Long time, TimeUnit unit) {
        redisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        //封装逻辑过期时间
        log.info("准备redisData----时间{}-----线程id：{}", LocalDateTime.now(), Thread.currentThread().getId());
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //存入redis
        redisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
        log.info("redisData存入redis成功----时间{}-----线程id：{}", LocalDateTime.now(), Thread.currentThread().getId());
    }

    /**
     * 设置空值解决缓存穿透
     *
     * @param keyPrefix  关键前缀
     * @param id         id
     * @param type       类型
     * @param dbFallback db回退
     * @param time       缓存时间
     * @param unit       单位
     * @return {@link R}
     */
    public <R, ID> R queryWithPassThrough(
            String keyPrefix
            , ID id
            , Class<R> type
            , Function<ID, R> dbFallback
            , Long time
            , TimeUnit unit) {
        String key = keyPrefix + id;
        //从redis中查询
        String json = redisTemplate.opsForValue().get(key);
        //判断是否存在
        if (StrUtil.isNotBlank(json)) {
            //存在直接返回
            return JSONUtil.toBean(json, type);
        }
        //判断空值
        if ("".equals(json)) {
            return null;
        }
        //不存在 查询数据库
        R r = dbFallback.apply(id);
        if (r == null) {
            //redis写入空值
            this.set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            //数据库不存在 返回错误
            return null;
        }
        //数据库存在 写入redis
        this.set(key, r, time, unit);
        //返回
        return r;
    }

    /**
     * 逻辑过期解决缓存击穿
     *
     * @param id id
     * @return {@link Shop}
     */
    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix,
            String lockPrefix
            , ID id
            , Class<R> type
            , Function<ID, R> dbFallback
            , Long time
            , TimeUnit unit) {
        String key = keyPrefix + id;
        String redisDataJson = redisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(redisDataJson)) {
            return null;
        }
        RedisData redisData = JSONUtil.toBean(redisDataJson, RedisData.class);
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            // 如果缓存未过期，直接返回
            return JSONUtil.toBean(JSONUtil.toJsonStr(redisData.getData()), type);
        }
        // 如果缓存已过期，尝试获取分布式锁
        String lockKey = lockPrefix + id;
        if (!tryLock(lockKey)) {
            // 如果获取锁失败，直接返回
            return JSONUtil.toBean(JSONUtil.toJsonStr(redisData.getData()), type);
        }
        // 如果获取锁成功，且仍然有必要更新缓存，那么开一个新线程去更新缓存
        redisDataJson = redisTemplate.opsForValue().get(key);
        redisData = JSONUtil.toBean(redisDataJson, RedisData.class);
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            unLock(lockKey);
            return JSONUtil.toBean(JSONUtil.toJsonStr(redisData.getData()), type);
        }
        CACHE_REBUILD_EXECUTOR.submit(() -> {
            try {
                log.info("即将休眠----时间{}-----线程id：{}", LocalDateTime.now(), Thread.currentThread().getId());
//                 Thread.sleep(1000);
                log.info("休眠结束，即将查询数据库----时间{}-----线程id：{}", LocalDateTime.now(), Thread.currentThread().getId());
                R r = dbFallback.apply(id); // 程序第一次启动时，连接数据库需要较长时间
                log.info("查询数据库结束----时间{}-----线程id：{}", LocalDateTime.now(), Thread.currentThread().getId());
                setWithLogicalExpire(key, r, time, unit);
                log.info("缓存重建成功----时间{}-----线程id：{}", LocalDateTime.now(), Thread.currentThread().getId());
            } catch (Exception e) {
                throw new RuntimeException("queryWithLogicalExpire" + e.getMessage());
            } finally {
                unLock(lockKey); // 释放锁
            }
        });
        return JSONUtil.toBean(JSONUtil.toJsonStr(redisData.getData()), type);
    }

    /**
     * 简易线程池
     */
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 获取锁
     *
     * @param key 关键
     * @return boolean
     */
    private boolean tryLock(String key) {
        Boolean flag = redisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁
     *
     * @param key 关键
     */
    private void unLock(String key) {
        redisTemplate.delete(key);
    }
}