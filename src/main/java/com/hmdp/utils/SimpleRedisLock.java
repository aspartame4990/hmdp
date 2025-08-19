package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.Objects;

public class SimpleRedisLock implements ILock {

    private StringRedisTemplate redisTemplate;
    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString() + "-";
    private String name;

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

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
        String expectedHolder = ID_PREFIX + Thread.currentThread().getId();
        redisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                expectedHolder
        );
    }
}
