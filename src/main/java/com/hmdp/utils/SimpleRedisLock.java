package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;

public class SimpleRedisLock implements ILock {

    private StringRedisTemplate redisTemplate;
    private static final String KEY_PREFIX = "lock:";
    private String name;

    public SimpleRedisLock(String name, StringRedisTemplate redisTemplate) {
        this.name = name;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        Boolean success = redisTemplate.opsForValue().setIfAbsent(
                KEY_PREFIX + name,
                Thread.currentThread().getId() + "",
                timeoutSec,
                java.util.concurrent.TimeUnit.SECONDS
        );
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        redisTemplate.delete(KEY_PREFIX + name);
    }
}
