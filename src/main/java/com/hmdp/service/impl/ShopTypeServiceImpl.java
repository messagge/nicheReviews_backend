package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryShopTypeList() {
        // CACHE_SHOP_TYPE_KEY = "cache:shopType";
        String key = CACHE_SHOP_TYPE_KEY;

        // 1 从 Redis 中查询商铺缓存
        List<String> shopTypeJsonList = stringRedisTemplate.opsForList().range(CACHE_SHOP_TYPE_KEY, 0, -1);

        // 2 判断 Redis 中是否有该缓存
        if (shopTypeJsonList != null && !shopTypeJsonList.isEmpty()) {
            // 2.1 若 Redis 中存在该缓存，则直接返回
            ArrayList<ShopType> shopTypes = new ArrayList<>();
            for (String str : shopTypeJsonList) {
                shopTypes.add(JSONUtil.toBean(str, ShopType.class));
            }
            return Result.ok(shopTypes);
        }
        // 2.2 Redis 中若不存在该数据，则从数据库中查询
        List<ShopType> shopTypes = query().orderByAsc("sort").list();

        // 3 判断数据库中是否存在
        if (shopTypes == null || shopTypes.isEmpty()) {
            // 3.1 数据库中不存在，则返回 false
            return Result.fail("分类不存在！");
        }

        // 3.2 数据库中存在，则将查询到的信息存入 Redis
        for (ShopType shopType : shopTypes) {
            stringRedisTemplate.opsForList().rightPushAll(CACHE_SHOP_TYPE_KEY, JSONUtil.toJsonStr(shopType));
        }

        // 4 返回
        return Result.ok(shopTypes);

    }


    @Override
    public Result queryShopTypeZSet() {
        // CACHE_SHOP_TYPE_KEY = "cache:shopType";
        String key = CACHE_SHOP_TYPE_KEY;
        // 1.从 Redis 中查询商铺缓存
        Set<String> shopTypeJsonSet = stringRedisTemplate.opsForZSet().range(CACHE_SHOP_TYPE_KEY, 0, -1);

        // 2.判断 Redis 中是否有该缓存
        if (shopTypeJsonSet.size() != 0) {
            // 2.1.若 Redis 中存在该缓存，则直接返回
            List<ShopType> shopTypes = new ArrayList<>();
            for (String str : shopTypeJsonSet) {
                shopTypes.add(JSONUtil.toBean(str, ShopType.class));
            }
            return Result.ok(shopTypes);
        }

        // 2.2.若 Redis 中无该数据的缓存，则查询数据库
        List<ShopType> shopTypes = query().orderByAsc("sort").list();

        // 3.判断数据库中是否存在
        if (shopTypes == null || shopTypes.isEmpty()) {
            // 3.1.数据库中不存在，则返回 false
            return Result.fail("分类不存在！");
        }

        // 3.2数据库中存在，则将查询到的信息存入 Redis
        for (ShopType shopType : shopTypes) {
            stringRedisTemplate.opsForZSet().add(CACHE_SHOP_TYPE_KEY,JSONUtil.toJsonStr(shopType),shopType.getSort());
        }

        // 4 返回
        return Result.ok(shopTypes);
    }

}
