package com.ticketflow.service.lua;

import com.alibaba.fastjson.JSON;
import com.ticketflow.observability.BusinessMetrics;
import com.ticketflow.observability.Metrics;
import com.ticketflow.redis.RedisCache;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Lua 脚本桥接：创建订单时的座位锁定 + 余票扣减。
 * 加载 programDataCreateOrderResolution.lua，在 Redis 中原子执行：
 * 验证顺序（座位存在/锁定/售出/余票充足/价格一致）→ 锁定座位 → 扣减余票 → 写流水记录。
 * <p>
 * 返回 ProgramCacheCreateOrderData 包含错误码（0=成功，40001~40011=各种失败）和已锁定座位列表
 * <p>
 * 被 BaseProgramOrder.create() 中的 Lua 锁块调用，是并发控制的核心
 */
@Slf4j
@Component
public class ProgramCacheCreateOrderResolutionOperate {

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private MeterRegistry meterRegistry;

    private DefaultRedisScript<String> redisScript;

    /**
     * 加载 Lua 脚本 programDataCreateOrderResolution.lua。
     * 该脚本在高并发下原子执行：验证座位状态 → 锁定座位 → 扣减余票 → 写入流水。
     */
    @PostConstruct
    public void init() {
        try {
            redisScript = new DefaultRedisScript<>();
            redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/programDataCreateOrderResolution.lua")));
            redisScript.setResultType(String.class);
        } catch (Exception e) {
            log.error("redisScript init lua error", e);
        }
    }

    /**
     * 执行创建订单的 Lua 脚本，返回锁定结果。
     *
     * @param keys Redis key 列表（节目座位/余票/流水等 Hash key）
     * @param args Lua 脚本参数（座位 ID、余票数量、价格等）
     * @return 包含错误码（0=成功）和已锁定座位列表的结果对象
     */
    public ProgramCacheCreateOrderData programCacheOperate(List<String> keys, String[] args) {
        long startNanos = System.nanoTime();
        // 先给默认值：Error/其他 Throwable 逃逸时 finally 也能安全埋点
        String resultTag = Metrics.RESULT_UNKNOWN;
        try {
            Object object = redisCache.getInstance().execute(redisScript, keys, args);
            ProgramCacheCreateOrderData result = JSON.parseObject((String) object, ProgramCacheCreateOrderData.class);
            resultTag = resultTag(result);
            return result;
        } catch (RuntimeException ex) {
            resultTag = Metrics.RESULT_ERROR;
            throw ex;
        } finally {
            double costSeconds = (System.nanoTime() - startNanos) / 1_000_000_000D;
            // 业务观测：扣减结果分桶 + 耗时分布（tags: version=V4, result=success/fail/limit/error/unknown）
            BusinessMetrics.increment(meterRegistry, Metrics.STOCK_DEDUCT_TOTAL,
                    Metrics.VERSION, Metrics.VERSION_V4, Metrics.RESULT, resultTag);
            BusinessMetrics.recordSeconds(meterRegistry, Metrics.STOCK_DEDUCT_DURATION_SECONDS, costSeconds,
                    Metrics.VERSION, Metrics.VERSION_V4, Metrics.RESULT, resultTag);
        }
    }

    /**
     * Lua 返回码 → 指标结果分桶。
     * code 说明：0=成功，其余（40001~40011）=座位/余票/价格校验失败。
     */
    private String resultTag(ProgramCacheCreateOrderData result) {
        if (result == null || result.getCode() == null) {
            return Metrics.RESULT_UNKNOWN;
        }
        int code = result.getCode();
        if (code == 0) {
            return Metrics.RESULT_SUCCESS;
        }
        return Metrics.RESULT_FAIL;
    }
}
