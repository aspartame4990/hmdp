package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.SystemConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
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

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 1. 判断是否需要根据坐标查询
        if (x == null || y == null) {
            Page<Shop> page = query().eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }
        // 2. 计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = (current) * SystemConstants.DEFAULT_PAGE_SIZE;
        // 3. 查询Redis、按照距离排序、分页。结果：shopId、distance
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = redisTemplate.opsForGeo().search(
                key,
                GeoReference.fromCoordinate(x, y),
                new Distance(5000),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
        );
        if (results == null) {
            return Result.ok();
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            // 没有下一页了，结束
            return Result.ok();
        }
        // 4. 解析出id
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        // 5. 根据id查询Shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        // 6. 返回
        return Result.ok(shops);
    }
}
