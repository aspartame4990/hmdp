package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import cn.hutool.core.bean.BeanUtil;
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
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
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
    private static final ExecutorService ORDER_TASK_EXECUTOR = Executors.newSingleThreadExecutor();

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @PostConstruct
    private void init() {
        ORDER_TASK_EXECUTOR.submit(() -> {
            String queueName = "stream.orders";
            while (true) {
                try {
                    //从消息队列中获取订单信息
                    List<MapRecord<String, Object, Object>> list = redisTemplate.opsForStream().read(Consumer.from("g1", "c1"), StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)), StreamOffset.create(queueName, ReadOffset.lastConsumed()));
                    //判断消息时候获取成功
                    if (list == null || list.isEmpty()) {
                        //获取失败 没有消息 继续循环
                        continue;
                    }
                    //获取成功 解析消息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //下单
                    proxy.createOrder(voucherOrder);
                    //ack确认消息
                    redisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    e.printStackTrace();
                    handlePendingList();
                }
            }
        });
    }

    private void handlePendingList() {
        String queueName = "stream.orders";
        while (true) {
            try {
                //从消息队列中获取订单信息
                List<MapRecord<String, Object, Object>> list = redisTemplate.opsForStream().read(Consumer.from("g1", "c1"), StreamReadOptions.empty().count(1), StreamOffset.create(queueName, ReadOffset.from("0")));
                //判断消息时候获取成功
                if (list == null || list.isEmpty()) {
                    //获取失败 没有消息 继续循环
                    break;
                }
                //获取成功 解析消息
                MapRecord<String, Object, Object> record = list.get(0);
                Map<Object, Object> values = record.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                //下单
                createOrder(voucherOrder);
                //ack确认消息
                redisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
            } catch (Exception e) {
                e.printStackTrace();
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                }
            }
        }
    }

    @Override
    public Result killVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        // 执行lua脚本
        Long result = redisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(), voucherId.toString(), userId.toString(), orderId + "");
        // 若结果为1，说明库存不足
        if (result.intValue() == 1) {
            return Result.fail("库存不足");
        }
        // 若结果为2，说明用户已下单
        if (result.intValue() == 2) {
            return Result.fail("用户已下单");
        }
        // 若结果为0，说明下单成功
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
        boolean success = seckillVoucherService.update().setSql("stock = stock - 1").eq("voucher_id", voucherId).gt("stock", 0).update();
        if (!success) {
            log.info("优惠券{}已经卖完了", voucherId);
            return null;
        }
        save(voucherOrder);
        log.info("用户{}购买优惠券{}成功，订单号为：{}", userId, voucherId, voucherOrder.getId());
        return voucherOrder;
    }
}
