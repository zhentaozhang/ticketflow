package com.ticketflow.service.lua;

import com.alibaba.fastjson.JSON;
import com.ticketflow.redis.RedisCache;
import com.ticketflow.vo.SeatVo;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
        // 超过阈值（2000）则并行流反序列化，提升大演出（3000-8000 座位）的处理速度
        if (seatVoStrlist.size() > THRESHOLD_VALUE) {
            list = seatVoStrlist.parallelStream()
                    .map(seatVoStr -> JSON.parseObject(seatVoStr,SeatVo.class)).collect(Collectors.toList());
        }else {
            list = seatVoStrlist.stream()
                    .map(seatVoStr -> JSON.parseObject(seatVoStr,SeatVo.class)).collect(Collectors.toList());
        }
        return list;
    }
}
