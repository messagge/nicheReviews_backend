package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.dto.Result.fail;
import static com.hmdp.dto.Result.ok;
import static com.hmdp.utils.RedisConstants.*;

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

    // 线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    @Resource
    StringRedisTemplate stringRedisTemplate;



    /**
     * Adding a Redis cache: Queries the store cache based on its id.
     * @param id
     * @return
     */
    @Override
    public Result queryShopById(Long id) {
        // 缓存穿透
        // queryWithPassThorough(id);

        // 互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);

        // 逻辑过期解决缓存击穿
        Shop shop = queryWithLogicExpired(id);

        if (shop == null) {
            return Result.fail("店铺不存在");
        }

        // 7 返回
        return Result.ok(shop);
    }

    /**
     * 逻辑过期解决缓存击穿
     * @param id
     * @return
     */
    public Shop queryWithLogicExpired(Long id) {
        String key = CACHE_SHOP_KEY + id;

        // 1.从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);

        // 2.判断是否存在
        if (StrUtil.isBlank(json)) {
            // 3. 未命中，直接返回
            return null;
        }

        // 4.命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();

        // 5.判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())) {
            // 5.1.未过期，直接返回店铺信息
            return shop;
        }
        // 5.2.已过期，需要缓存重建
        // 6.缓存重建
        // 6.1.获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        // 6.2.判断是否获取锁成功
        if (isLock){
            CACHE_REBUILD_EXECUTOR.submit( ()->{
                // 6.3 成功，开启独立线程
                try{
                    //重建缓存
                    this.saveShopToRedis(id,20L);
                }catch (Exception e){
                    throw new RuntimeException(e);
                }finally {
                    unLock(lockKey);
                }
            });
        }
        // 6.4.返回过期的商铺信息
        return shop;
    }

    /**
     * 使用互斥锁解决缓存击穿
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;

        // 1 从redis中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2 判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 3 商户真实存在，返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        // 判断缓存命中的是否为空值
        if (shopJson != null) {
            return null;
        }

        // 4 实现缓存重建
        // 4.1 获取锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        ///** ctrl + alt + t   快捷键，选中加 **///
        try {
            boolean isLock = tryLock(lockKey);

            // 4.2 判断是否获取成功
            if (!isLock) {
                // 4.3 失败，休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }

            // 4.4 成功，
            // 4.4.1 再次检查Redis，缓存是否存在
            shopJson = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(shopJson)) {
                // 商户真实存在，返回
                return JSONUtil.toBean(shopJson, Shop.class);
            }

            // 若不存在，则根据id查询数据库
            shop = getById(id);
            // 模拟延时
            Thread.sleep(200);
            if (shop == null) {
                // 5 数据库中不存在，返回错误
                // 数据库中不存在，将空值写入缓存
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }

            // 6 数据库中存在，写入redis，并添加超时时间30m
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException();
        } finally {
            // 7 释放锁
            unLock(lockKey);
        }

        // 8 返回
        return shop;
    }

    /**
     * 解决缓存穿透
     * @param id
     * @return
     */
    public Shop queryWithPassThorough(Long id) {
        String key = CACHE_SHOP_KEY + id;

        // 1 从redis中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2 判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 3 商户真实存在，返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        // 判断缓存命中的是否为空值
        if (shopJson != null) {
            return null;
        }

        // 4 不存在，查询数据库
        Shop shop = getById(id);
        if (shop == null) {
            // 5 数据库中不存在，返回错误
            // 数据库中不存在，将空值写入缓存
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        // 6 数据库中存在，写入redis，并添加超时时间30m
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 7 返回
        return shop;
    }


        /**
         * 获取锁
         * @param key
         * @return
         */
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10L, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁
     * @param key
     */
    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

    public void saveShopToRedis(Long id, Long expireTime) {
        Shop shop = getById(id);
        // 分装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireTime));
        redisData.setData(shop);
        // 写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));

    }


    /**
     * 确保缓存与数据库中商户信息一致，使用事务
     * @param shop
     * @return
     */
    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        // 1 更新数据库
        updateById(shop);

        // 2 删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);

        return Result.ok();
    }
}
