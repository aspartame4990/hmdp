package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private RedisIdWorker redisIdWorker;
    @Autowired
    private StringRedisTemplate redisTemplate;
//    @Autowired @Lazy
//    private VoucherOrderServiceImpl proxy;

    @Override
    public Result killVoucher(Long voucherId) {
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀未开始");
        }
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已结束");
        }
        if (voucher.getStock() < 1) {
            return Result.fail("优惠券卖完了");
        }
        Long userId = UserHolder.getUser().getId();
        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, redisTemplate);
        boolean success = lock.tryLock(1200);
        if (!success) {
            return Result.fail("请勿重复下单");
        }
        // 使用代理对象调用事务方法，避免事务失效
        VoucherOrderServiceImpl proxy = (VoucherOrderServiceImpl) AopContext.currentProxy();
        VoucherOrder voucherOrder = proxy.createOrder(voucherId, userId);
        if (voucherOrder == null) {
            log.info("创建订单失败，用户{}购买优惠券{}失败", userId, voucherId);
            lock.unlock();
            return Result.fail("下单失败");
        }
        lock.unlock();
        return Result.ok(voucherOrder.getId());
    }

    @Transactional
    public VoucherOrder createOrder(Long voucherId, Long userId) {
        Long userVoucherCount = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        log.info("用户{}购买优惠券{}的数量为：{}", userId, voucherId, userVoucherCount);
        if (userVoucherCount > 0) {
            log.info("用户{}已经购买过优惠券{}，不允许重复购买", userId, voucherId);
            return null;
        }
        boolean success = seckillVoucherService.update().setSql("stock = stock - 1").
                eq("voucher_id", voucherId).
                gt("stock", 0).
                update();
        if (!success) {
            log.info("优惠券{}已经卖完了", voucherId);
            return null;
        }
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(redisIdWorker.nextId("order"));
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setUserId(userId);
        save(voucherOrder);
        log.info("用户{}购买优惠券{}成功，订单为：{}", userId, voucherId, voucherOrder);
        return voucherOrder;
    }
}
