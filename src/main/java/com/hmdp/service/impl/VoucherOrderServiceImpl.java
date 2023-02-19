package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
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


    @Override
    @Transactional
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

        // 5 充足，扣减库存
        // 加乐观锁
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock", 0)
                .update(); //where id = ？ and stock > 0
        if (!success) {
            return Result.fail("库存不足");
        }

        // 6 建立订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 6.1 订单id
        Long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 6.2 用户id
        Long userId = UserHolder.getUser().getId();
        voucherOrder.setUserId(userId);
        // 6.3 代金券id
        voucherOrder.setVoucherId(voucherId);
        // 保存订单
        save(voucherOrder);

        // 7 返回订单id
        return Result.ok(orderId);
    }
}
