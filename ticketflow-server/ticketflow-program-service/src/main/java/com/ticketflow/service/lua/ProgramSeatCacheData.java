package com.ticketflow.service.lua;

import com.alibaba.fastjson.JSON;
import com.ticketflow.redis.RedisCache;
import com.ticketflow.vo.SeatVo;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;

/**
 * 节目座位缓存数据操作。封装Redis Lua脚本执行，实现座位锁和余票扣减的原子操作。
 */
@Slf4j
@Component
public class ProgramSeatCacheData {
    
    @Autowired
    private RedisCache redisCache;
    
    private DefaultRedisScript redisScript;
    
    /**
     * 单次查询超过 2000 个座位时启用 parallelStream 并行处理。
     * 大型演出的座位数通常在 3000-8000，2000 以下串行足够快，避免并行调度开销。
     */
    private static final Integer THRESHOLD_VALUE = 2000;

    /**
     * 座位反序列化专用并行池。
     * 不用 commonPool：公共池承载 JVM 内所有并行流/并行任务，座位大列表反序列化会与其它
     * 并行操作互相抢线程。这里自建一个有界池，随 Bean 生命周期关闭。
     */
    private final ForkJoinPool seatParsePool =
            new ForkJoinPool(Math.min(8, Math.max(2, Runtime.getRuntime().availableProcessors())));

    @PreDestroy
    public void destroy() {
        seatParsePool.shutdown();
    }
    
    /**
     * 加载 Lua 脚本 programSeat.lua，设置返回类型为 Object。
     * 该脚本从 Redis Hash 中批量读取座位数据（座位 ID → JSON 字符串的映射）。
     */
    @PostConstruct
    public void init(){
        try {
            redisScript = new DefaultRedisScript<>();
            redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/programSeat.lua")));
            redisScript.setResultType(Object.class);
        } catch (Exception e) {
            log.error("redisScript init lua error",e);
        }
    }
    
    /**
     * 执行 Lua 脚本批量获取座位数据，并将 JSON 字符串反序列化为 SeatVo 对象。
     *
     * @param keys Redis key 列表（节目座位 Hash 的 key）
     * @param args Lua 脚本参数（座位 ID 列表）
     * @return 反序列化后的座位列表
     */
    public List<SeatVo> getData(List<String> keys, String[] args){
        List<SeatVo> list;
        Object object = redisCache.getInstance().execute(redisScript, keys, args);
        List<String> seatVoStrlist = new ArrayList<>();
        if (Objects.nonNull(object) && object instanceof ArrayList) {
            seatVoStrlist = (ArrayList<String>)object;
        }
        // 超过阈值（2000）在专用池里并行反序列化，提升大演出（3000-8000 座位）的处理速度；
        // 在自建池的任务内调用 parallelStream，流会复用该池而不是 commonPool。
        if (seatVoStrlist.size() > THRESHOLD_VALUE) {
            List<String> parseSource = seatVoStrlist;
            try {
                list = seatParsePool.submit(() -> parseSource.parallelStream()
                        .map(seatVoStr -> JSON.parseObject(seatVoStr, SeatVo.class))
                        .collect(Collectors.toList())).get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("座位缓存并行反序列化被中断", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new IllegalStateException("座位缓存并行反序列化失败", cause);
            }
        } else {
            list = seatVoStrlist.stream()
                    .map(seatVoStr -> JSON.parseObject(seatVoStr,SeatVo.class)).collect(Collectors.toList());
        }
        return list;
    }
}
