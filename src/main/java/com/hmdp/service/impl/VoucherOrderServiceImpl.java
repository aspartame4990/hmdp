package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ExecutorService;

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
    @Autowired
    @Lazy
    private IVoucherOrderService proxy;
    @Autowired
    private RedissonClient redissonClient;

    private static final BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    private static final ExecutorService ORDER_TASK_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init() {
        // 启动订单处理线程
        ORDER_TASK_EXECUTOR.submit(new VoucherOrderHandler());
        log.info("订单处理线程已启动");
    }

    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    // 从阻塞队列中获取订单
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 创建订单
                    if (proxy.createOrder(voucherOrder) == null) {
                        log.error("创建订单失败，订单信息：{}", voucherOrder);
                    } else {
                        log.info("成功处理订单，订单信息：{}", voucherOrder);
                    }
                } catch (InterruptedException e) {
                    log.error("订单处理线程被中断", e);
                    Thread.currentThread().interrupt(); // 恢复中断状态
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Override
    public Result killVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        // 执行lua脚本
        Long result = redisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString()
        );
        // 若结果为1，说明库存不足
        if (result.intValue() == 1) {
            return Result.fail("库存不足");
        }
        // 若结果为2，说明用户已下单
        if (result.intValue() == 2) {
            return Result.fail("用户已下单");
        }
        // 若结果为0，说明下单成功
        long orderId = redisIdWorker.nextId("order");
        // 保存到阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setUserId(userId);
        orderTasks.add(voucherOrder);
        // 返回订单ID
        return Result.ok(orderId);
    }

    @Transactional
    public VoucherOrder createOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
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
        save(voucherOrder);
        log.info("用户{}购买优惠券{}成功，订单号为：{}", userId, voucherId, voucherOrder.getId());
        return voucherOrder;
    }
}
