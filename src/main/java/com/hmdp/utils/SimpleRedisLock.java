package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Objects;

public class SimpleRedisLock implements ILock {

    private StringRedisTemplate redisTemplate;
    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString() + "-";
    private String name;

    public SimpleRedisLock(String name, StringRedisTemplate redisTemplate) {
        this.name = name;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        Boolean success = redisTemplate.opsForValue().setIfAbsent(
                KEY_PREFIX + name,
                 ID_PREFIX + Thread.currentThread().getId(),
                timeoutSec,
                java.util.concurrent.TimeUnit.SECONDS
        );
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        String lockHolder = redisTemplate.opsForValue().get(KEY_PREFIX + name);
        String expectedHolder = ID_PREFIX + Thread.currentThread().getId();
        if (Objects.equals(lockHolder, expectedHolder)) {
            redisTemplate.delete(KEY_PREFIX + name);
        }
    }
}
