package com.ticketflow.service.stock;

import com.ticketflow.core.RedisKeyManage;
import com.ticketflow.redis.RedisCache;
import com.ticketflow.redis.RedisKeyBuild;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Iterator;
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
 *   <li>闸门值 = Lua 成功后精确覆盖（remainMap 为扣减后余票）+ 定时从 Redis 余票 hash 刷新（2s）收敛；</li>
 *   <li>闸门与 Redis 短暂不一致（如取消回补余票）时，最多造成 ≤2s 的"假售罄"，由下次刷新纠正；</li>
 *   <li>闸门显示有票但实际无票 → 请求照常走 Lua，由 Lua 返回余票不足。</li>
 * </ul>
 * 仅对"已发生过下单"的热门票档生效（惰性跟踪）；从未跟踪的票档返回 -1 表示不拦截。
 * <p>
 * 闸门只用于"提前拒绝"，任何"提前放行/业务决策"都必须由 Lua 裁决——本类不返回任何可放行的权威信号。
 */
@Slf4j
@Component
public class ProgramLocalStockGate {

    /**
     * 已售罄（remain==0）且空闲超过该时长后，从"活跃拦截"转为"售罄态"：
     * 不再占用定时刷新的 Redis 读取，但保留 0 拦截语义（售罄短路不因闲置而丢失）。
     */
    private static final long SOLD_OUT_IDLE_MILLIS = 30 * 60 * 1000L;

    /**
     * 售罄态条目最长保留时长：超过后整体移除（内存回收）。
     * 该票档若再次有库存（reset/预热），会重新被 track 跟踪，不依赖旧条目。
     */
    private static final long SOLD_OUT_MAX_LIVE_MILLIS = 6 * 60 * 60 * 1000L;

    /**
     * refresh 读到 nil（余票 hash 缺失，如 reset 后/预热前）时的放行哨兵：
     * 余票未知时宁可通过（由 Lua 裁决），也不因旧值误拒绝。
     */
    private static final int UNKNOWN_ALLOW_SENTINEL = Integer.MAX_VALUE;

    private static class GateEntry {
        final AtomicInteger remain;
        final AtomicLong lastAccess = new AtomicLong(System.currentTimeMillis());
        /** 已确认售罄（remain==0 且闲置超时）：返回 0 拦截但不再参与 2s 轮询刷新 */
        volatile boolean soldOut;

        GateEntry(int remain) {
            this.remain = new AtomicInteger(Math.max(remain, 0));
        }
    }

    private final Map<String, GateEntry> gate = new ConcurrentHashMap<>();

    @Autowired
    private RedisCache redisCache;

    private String gateKey(Long programId, Long ticketCategoryId) {
        return programId + ":" + ticketCategoryId;
    }

    /**
     * 读取预估余票。未跟踪返回 -1（不拦截）；已确认售罄返回 0（拦截）。
     */
    public int estimatedRemain(Long programId, Long ticketCategoryId) {
        GateEntry entry = gate.get(gateKey(programId, ticketCategoryId));
        if (entry == null) {
            return -1;
        }
        entry.lastAccess.set(System.currentTimeMillis());
        return entry.soldOut ? 0 : entry.remain.get();
    }

    /**
     * 跟踪/更新一个票档的预估余票（精确覆盖语义）。
     * 每次 Lua 扣减成功后都会用扣减后余票调用本方法，直接覆盖旧值，
     * 保证闸门在两次 2s 刷新之间也是最新值（不再有"computeIfAbsent 只生效一次"的滞后）。
     *
     * @param remain Lua 返回的扣减后余票（>=0）
     */
    public void track(Long programId, Long ticketCategoryId, int remain) {
        String key = gateKey(programId, ticketCategoryId);
        gate.compute(key, (k, entry) -> {
            if (entry == null) {
                return new GateEntry(Math.max(remain, 0));
            }
            entry.remain.set(Math.max(remain, 0));
            entry.soldOut = false;
            entry.lastAccess.set(System.currentTimeMillis());
            return entry;
        });
    }

    /**
     * 清空指定节目的全部闸门条目（reset/预热时调用，避免旧余票残留导致假售罄）。
     */
    public void clear(Long programId) {
        String prefix = programId + ":";
        gate.keySet().removeIf(key -> key.startsWith(prefix));
        log.info("本地库存闸门已清空 programId : {}", programId);
    }

    /**
     * 定时从 Redis 余票 hash 刷新所有已跟踪票档（校正取消/回补/库存重置带来的漂移）。
     * 余票 hash 按 (programId, ticketCategoryId) 分 key，每票档每 2s 一次 Redis 读取。
     * <p>
     * nil 处理：余票字段缺失（reset 后/preheat 前）视为"未知"，置放行哨兵而非保留旧值，
     * 避免基于过期旧值持续误拒绝；真实余票为 0 时字段值为 "0"（Lua HINCRBY 不会删字段），不会误判。
     * <p>
     * 售罄退化：remain==0 且空闲超过阈值 → 置 soldOut（保留 0 拦截语义），不再参与后续轮询；
     * soldOut 条目存活超过上限后移除（内存回收，由 track 重新拉起）。
     */
    @Scheduled(fixedDelay = 2000)
    public void refresh() {
        if (gate.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, GateEntry>> iterator = gate.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, GateEntry> gateEntry = iterator.next();
            String key = gateEntry.getKey();
            GateEntry entry = gateEntry.getValue();
            if (entry.soldOut) {
                // 售罄态不参与轮询；超时后整体移除回收内存
                if (now - entry.lastAccess.get() > SOLD_OUT_MAX_LIVE_MILLIS) {
                    iterator.remove();
                }
                continue;
            }
            int idx = key.indexOf(':');
            if (idx <= 0) {
                continue;
            }
            Long programId;
            Long categoryId;
            try {
                programId = Long.valueOf(key.substring(0, idx));
                categoryId = Long.valueOf(key.substring(idx + 1));
            } catch (NumberFormatException e) {
                continue;
            }
            try {
                String remainStr = redisCache.getForHash(
                        RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_TICKET_REMAIN_NUMBER_HASH_RESOLUTION, programId, categoryId),
                        String.valueOf(categoryId), String.class);
                if (remainStr == null) {
                    // 余票字段缺失（reset 后/预热前）：置放行哨兵，不保留旧值
                    entry.remain.set(UNKNOWN_ALLOW_SENTINEL);
                    continue;
                }
                int remain;
                try {
                    remain = Math.max(Integer.parseInt(remainStr), 0);
                } catch (NumberFormatException ignored) {
                    entry.remain.set(UNKNOWN_ALLOW_SENTINEL);
                    continue;
                }
                entry.remain.set(remain);
                if (remain == 0 && now - entry.lastAccess.get() > SOLD_OUT_IDLE_MILLIS) {
                    // 已确认售罄且长时间无请求：转售罄态，保留拦截语义并停止轮询
                    entry.soldOut = true;
                }
            } catch (Exception e) {
                log.warn("本地库存闸门刷新失败 programId : {} ticketCategoryId : {}", programId, categoryId, e);
            }
        }
    }
}
