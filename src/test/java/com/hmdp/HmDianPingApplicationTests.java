package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.redisson.RedissonMultiLock;
import org.redisson.api.RedissonClient;
import org.redisson.api.RLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private RedissonClient redissonClient;
    @Resource
    private RedissonClient redissonClient2;
    @Resource
    private RedissonClient redissonClient3;

    @Autowired
    private StringRedisTemplate redisTemplate;


    @Test
    void preLoadCache() throws Exception {
        cacheClient.setWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY + "1", shopService.getById(1L),
                1L, TimeUnit.SECONDS);
    }

    @Test
    void testIdWorker() {
        long pre_id = redisIdWorker.nextId("waibibabi");
        for (int i = 0; i < 1000; i++) {
            long id = redisIdWorker.nextId("waibibabi");
            assert id > pre_id : "ID should be greater than previous ID";
            pre_id = id;
        }
    }

    @Test
    void testRLockReentrant() throws InterruptedException {
        RLock lock = redissonClient.getLock("test:rlock:reentrant");

        // 第一次加锁：应立即成功
        boolean first = lock.tryLock(0, 30, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(first, "第一次加锁应成功");
        assertTrue(lock.isHeldByCurrentThread(), "加锁后应由当前线程持有");
        assertEquals(1, lock.getHoldCount(), "第一次加锁后持有计数应为 1");

        try {
            // 第二次加锁（同一线程，同一把锁）：应立即成功（可重入）
            boolean second = lock.tryLock(0, 30, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(second, "第二次加锁（可重入）应成功");
            assertTrue(lock.isHeldByCurrentThread(), "第二次加锁后仍应由当前线程持有");
            assertEquals(2, lock.getHoldCount(), "第二次加锁后持有计数应为 2");

            // 释放一次：计数应减为 1，锁仍由当前线程持有
            lock.unlock();
            assertTrue(lock.isHeldByCurrentThread(), "释放一次后仍应由当前线程持有");
            assertEquals(1, lock.getHoldCount(), "释放一次后持有计数应为 1");

        } finally {
            // 最终释放：计数为 0，锁应完全释放
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }

        assertFalse(lock.isLocked(), "完全释放后锁应处于未加锁状态");
    }


    @Test
    void testMultiLock_basicAndRollback() throws Exception {
        String key = "test:multilock:demo";

        RLock l1 = redissonClient.getLock(key);
        RLock l2 = redissonClient2.getLock(key);
        RLock l3 = redissonClient3.getLock(key);

        RedissonMultiLock m1 = new RedissonMultiLock(l1, l2, l3);

        assertTrue(m1.tryLock(1, -1, TimeUnit.SECONDS), "主线程应成功获取 MultiLock");
        try {
            assertTrue(l1.isHeldByCurrentThread());
            assertTrue(l2.isHeldByCurrentThread());
            assertTrue(l3.isHeldByCurrentThread());
        } finally {
            m1.unlock();
        }

        assertFalse(l1.isLocked(), "释放后分片1应未加锁");
        assertFalse(l2.isLocked(), "释放后分片2应未加锁");
        assertFalse(l3.isLocked(), "释放后分片3应未加锁");
    }

    @Test
    public void testLoadShopData() {
        //查询店铺信息
        List<Shop> list = shopService.list();
        //分组
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        //分批写入
        for (Map.Entry<Long, List<Shop>> longListEntry : map.entrySet()) {
            //获取类型id
            Long typeId = longListEntry.getKey();
            //获取店铺集合
            List<Shop> value = longListEntry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations=new ArrayList<>(value.size());
            //写入redis
            String key = SHOP_GEO_KEY + typeId;
            /*for (Shop shop : value) {
                Double x = shop.getX();
                Double y = shop.getY();
                stringRedisTemplate.opsForGeo()
                        .add(key
                                , new Point(x, y)
                                , shop.getId().toString());
            }*/
            for (Shop shop : value) {
                locations.add(new RedisGeoCommands.GeoLocation<>(shop.getId().toString(),new Point(shop.getX(),shop.getY())));
            }
            redisTemplate.opsForGeo().add(key,locations);
        }
    }


    @Test
    public void testHyperLogLog(){
        String[] values=new String[1000];
        int j=0;
        for (int i = 0; i < 1000000; i++) {
            j=i%1000;
            values[j]="user_"+i;
            if (j==999){
                redisTemplate.opsForHyperLogLog().add("hl1",values);
            }
        }
        Long hl1 = redisTemplate.opsForHyperLogLog().size("hl1");
        System.out.println(hl1);
    }
}