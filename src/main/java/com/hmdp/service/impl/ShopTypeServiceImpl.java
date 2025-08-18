package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Override
    public List<ShopType> typeList() {
        String key = "shopTypeList";
        // 1. 从Redis中查询类型信息
        List<String> typeJson = redisTemplate.opsForList().range(key, 0, -1);
        if (typeJson != null && !typeJson.isEmpty()) {
            return typeJson.stream()
                    .map(json -> JSONUtil.toBean(json, ShopType.class)).collect(Collectors.toList());
        }
        // 2. 如果Redis中没有，查询数据库
        List<ShopType> typeList = query().orderByAsc("sort").list();
        if (typeList == null || typeList.isEmpty()) {
            return null;
        }
        // 3. 存入Redis
        redisTemplate.opsForList().rightPushAll(key,
                typeList.stream().map(JSONUtil::toJsonStr).collect(Collectors.toList()));
        // 4. 返回
        return typeList;
    }
}
