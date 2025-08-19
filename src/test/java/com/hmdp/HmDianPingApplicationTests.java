package com.hmdp;

import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Test
    void preLoadCache() throws Exception {
        cacheClient.setWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY + "1", shopService.getById(1L),
                1L, TimeUnit.SECONDS);
    }

    @Test
    void testIdWorker() {
        long pre_id = redisIdWorker.nextId("waibibabi");
        for (int i = 0; i < 100000; i++) {
            long id = redisIdWorker.nextId("waibibabi");
            assert id > pre_id : "ID should be greater than previous ID";
            pre_id = id;
        }
    }
}