package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        try {
            Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, LOCK_SHOP_KEY, id, Shop.class, this::getById,
                    CACHE_SHOP_TTL, TimeUnit.MINUTES);
//                    1L, TimeUnit.SECONDS);
            if (shop == null) {
                return Result.fail("商铺信息不存在");
            }
            return Result.ok(shop);
        } catch (Exception e) {
            return Result.fail("查询商铺信息失败: " + e.getMessage());
        }
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        // 1. 校验商铺id是否存在
        if (shop.getId() == null) {
            return Result.fail("商铺id不能为空");
        }
        // 2. 更新数据库中的商铺信息
        updateById(shop);
        // 3. 删除Redis中的商铺缓存
        redisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }

    private boolean tryLock(String key) {
        Boolean success = redisTemplate.opsForValue().setIfAbsent(
                key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(success);
    }

    private void unlock(String key) {
        redisTemplate.delete(key);
    }

    public Shop queryWithMutex(Long id) throws InterruptedException {
        String key = CACHE_SHOP_KEY + id;
        // 1. 先查redis，若Redis中有缓存，分两种情况，字符串非空和空串
        String shopJson = redisTemplate.opsForValue().get(key);
        if (!StrUtil.isBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        if (shopJson != null && Objects.equals(shopJson, "")) {
            return null;
        }
        // 2. 如果Redis中没有，查询数据库，分两种情况，数据库中不存在和存在
        assert (shopJson == null);
        String lock = LOCK_SHOP_KEY + id;
        if (!tryLock(lock)) {
            Thread.sleep(50);
            return queryWithMutex(id); // 重新查缓存
        }
        shopJson = redisTemplate.opsForValue().get(key);
        if (!StrUtil.isBlank(shopJson)) {
            unlock(lock);
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        if (shopJson != null && Objects.equals(shopJson, "")) {
            unlock(lock);
            return null;
        }
        Thread.sleep(5000); // 模拟数据库查询延时
        Shop shop = getById(id);
        if (shop == null) {
            redisTemplate.opsForValue().set(key, "",
                    CACHE_NULL_TTL, TimeUnit.MINUTES);
            unlock(lock);
            return null;
        }
        redisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),
                CACHE_SHOP_TTL, TimeUnit.MINUTES);
        unlock(lock);
        return shop;
    }
}
