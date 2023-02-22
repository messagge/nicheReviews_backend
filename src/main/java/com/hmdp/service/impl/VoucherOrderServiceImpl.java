package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    /**
     * 秒杀劵服务
     */
    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        // 在ClassPath（Resource）下寻找资源
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        int r = result.intValue();
        // 2.判断结果是否为0
        if (r != 0) {
            // 2.1.不为0 ，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        //TODO 保存阻塞队列
        // 3.返回订单id
        return Result.ok(orderId);
    }

    /*@Override
    public Result seckillVoucher(Long voucherId) {
        // 1 查询优惠卷
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);

        // 2 判断秒杀是否开始
        LocalDateTime beginTime = voucher.getBeginTime();
        // 尚未开始：开始时间在当前时间后面
        if (beginTime.isAfter(LocalDateTime.now())) {
            return Result.fail("时间尚未开始");
        }

        // 3 判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀结束");
        }

        // 4 判断库存是否充足
        Integer stock = voucher.getStock();
        if (stock < 1) {
            return Result.fail("库存不足");
        }

        *//*Long userId = UserHolder.getUser().getId();
        // 给相同的用户id值上锁
        synchronized (userId.toString().intern()) {
            // 事务想要生效，还得利用代理来生效，所以这个地方，
            // 我们需要获得原始的事务对象，来操作事务
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }*//*

        Long userId = UserHolder.getUser().getId();
        // 创建锁
//        SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate, "order:" + userId);
        RLock lock = redissonClient.getLock("order:" + userId);

        // 获取锁
        boolean isLock = lock.tryLock();

        // 判断锁是否获取成功
        if (!isLock) {
            // 获取失败，返回错误信息
            return Result.fail("不允许重复下单！！");
        }
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            // 释放锁
            lock.unlock();
        }
    }*/

    @Override
    @Transactional
    public synchronized Result createVoucherOrder(Long voucherId) {

        // 5 一人一单
        Long userId = UserHolder.getUser().getId();


        // 根据用户id和优惠卷id查询是否存在订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            // 用户已经购买过了
            return Result.fail("用户已经购买过一次！！");
        }

        // 6 充足，扣减库存
        // 加乐观锁
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock", 0)
                .update(); //where id = ？ and stock > 0

        if (!success) {
            return Result.fail("库存不足");
        }


        // 7 建立订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 7.1 订单id
        Long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 7.2 用户id

        voucherOrder.setUserId(userId);
        // 7.3 代金券id
        voucherOrder.setVoucherId(voucherId);
        // 保存订单
        save(voucherOrder);

        //  返回订单id
        return Result.ok(orderId);

    }

}
