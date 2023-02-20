package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {

    private static final String KEY_PERFIX = "lock";
    private static final String ID_PERFIX = UUID.randomUUID().toString(true) + "-";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    private StringRedisTemplate stringRedisTemplate;
    private String name;

    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        // 在ClassPath（Resource）下寻找资源
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    @Override
    public boolean tryLock(long timeoutSecond) {
        String threadLockId = ID_PERFIX + Thread.currentThread().getId();

        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PERFIX + name, threadLockId, timeoutSecond, TimeUnit.SECONDS);
        // 防止空指针
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {

        // 调用lua脚本
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PERFIX + name),
                ID_PERFIX + Thread.currentThread().getId());
    }

/*
    @Override
    public void unlock() {
        String threadLockId = ID_PERFIX + Thread.currentThread().getId();
        String id = stringRedisTemplate.opsForValue().get(KEY_PERFIX + name);
        // 判断标识是否一致
        if (threadLockId.equals(id)) {
            stringRedisTemplate.delete(KEY_PERFIX + name);
        }
    }
    */
}
