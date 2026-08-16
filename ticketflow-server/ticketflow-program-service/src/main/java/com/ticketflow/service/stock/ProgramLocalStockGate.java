package com.ticketflow.service.stock;

import com.ticketflow.core.RedisKeyManage;
import com.ticketflow.redis.RedisCache;
import com.ticketflow.redis.RedisKeyBuild;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本地库存闸门（V5）。
 * 在应用内存维护每票档的"预估余票"，用于在请求到达 Redis 之前快速拒绝已售罄的请求，
 * 售罄短路：开抢后大部分请求命中已售罄，直接本地拒绝，不消耗 Redis 命令。
 * <p>
 * 一致性说明：闸门只是"预判加速器"，不是权威。正确性永远由 Redis Lua 原子扣减裁决：
 * <ul>
 *   <li>闸门值 = Lua 成功后递减 + 定时从 Redis 余票 hash 刷新（2s）收敛；</li>
 *   <li>闸门与 Redis 短暂不一致（如取消回补余票）时，最多造成 ≤2s 的"假售罄"，由下次刷新纠正；</li>
 *   <li>闸门显示有票但实际无票 → 请求照常走 Lua，由 Lua 返回余票不足。</li>
 * </ul>
 * 仅对"已发生过下单"的热门票档生效（惰性跟踪）；从未跟踪的票档返回 -1 表示不拦截。
 */
@Slf4j
@Component
public class ProgramLocalStockGate {

    private static final long IDLE_EVICT_MILLIS = 30 * 60 * 1000L;

    private static class GateEntry {
        final AtomicInteger remain;
        final AtomicLong lastAccess = new AtomicLong(System.currentTimeMillis());

        GateEntry(int remain) {
            this.remain = new AtomicInteger(remain);
        }
    }

    private final Map<String, GateEntry> gate = new ConcurrentHashMap<>();

    @Autowired
    private RedisCache redisCache;

    private String gateKey(Long programId, Long ticketCategoryId) {
        return programId + ":" + ticketCategoryId;
    }

    /**
     * 读取预估余票。未跟踪返回 -1（不拦截）。
     */
    public int estimatedRemain(Long programId, Long ticketCategoryId) {
        GateEntry entry = gate.get(gateKey(programId, ticketCategoryId));
        if (entry == null) {
            return -1;
        }
        entry.lastAccess.set(System.currentTimeMillis());
        return entry.remain.get();
    }

    /**
     * 跟踪并初始化一个票档的预估余票（幂等，已存在则忽略）。
     */
    public void track(Long programId, Long ticketCategoryId, int remain) {
        String key = gateKey(programId, ticketCategoryId);
        gate.computeIfAbsent(key, k -> new GateEntry(Math.max(remain, 0)));
    }

    /**
     * Lua 扣减成功后递减闸门。
     */
    public void deduct(Long programId, Long ticketCategoryId, int count) {
        GateEntry entry = gate.get(gateKey(programId, ticketCategoryId));
        if (entry != null) {
            entry.lastAccess.set(System.currentTimeMillis());
            int now = entry.remain.addAndGet(-count);
            entry.remain.set(Math.max(now, 0));
        }
    }

    /**
     * 定时从 Redis 余票 hash 刷新所有已跟踪票档（校正取消/回补/库存重置带来的漂移）。
     * 余票 hash 按 (programId, ticketCategoryId) 分 key，每票档每 2s 一次 Redis 读取。
     */
    @Scheduled(fixedDelay = 2000)
    public void refresh() {
        if (gate.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        gate.forEach((key, entry) -> {
            int idx = key.indexOf(':');
            Long programId = Long.valueOf(key.substring(0, idx));
            Long categoryId = Long.valueOf(key.substring(idx + 1));
            try {
                String remainStr = redisCache.getForHash(
                        RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_TICKET_REMAIN_NUMBER_HASH_RESOLUTION, programId, categoryId),
                        String.valueOf(categoryId), String.class);
                if (remainStr != null) {
                    try {
                        entry.remain.set(Math.max(Integer.parseInt(remainStr), 0));
                    } catch (NumberFormatException ignored) {
                    }
                }
            } catch (Exception e) {
                log.warn("本地库存闸门刷新失败 programId : {} ticketCategoryId : {}", programId, categoryId, e);
            }
            // 超过空闲阈值且余票为 0 的票档从闸门剔除，避免冷票档无限占用内存
            if (now - entry.lastAccess.get() > IDLE_EVICT_MILLIS && entry.remain.get() == 0) {
                gate.remove(key);
            }
        });
    }
}
