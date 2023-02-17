package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    StringRedisTemplate stringRedisTemplate;

    /**
     * Adding a Redis cache: Queries the store cache based on its id.
     * @param id
     * @return
     */
    @Override
    public Result queryShopById(Long id) {
        String key = CACHE_SHOP_KEY + id;

        // 1 从redis中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2 判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 3 存在，返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }

        // 4 不存在，查询数据库
        Shop shop = getById(id);
        if (shop == null) {
            // 5 数据库中不存在，返回错误
            return Result.fail("店铺不存在");
        }

        // 6 数据库中存在，写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop));

        // 7 返回
        return Result.ok(shop);
    }
}
