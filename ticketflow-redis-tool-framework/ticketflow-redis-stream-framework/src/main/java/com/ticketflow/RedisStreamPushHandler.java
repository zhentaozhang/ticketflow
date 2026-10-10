package com.ticketflow;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.ObjectRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis Stream消息发送器。向Redis Stream中添加消息并记录ID。
 */
@Slf4j
@AllArgsConstructor
public class RedisStreamPushHandler {


    private final StringRedisTemplate stringRedisTemplate;

    private final RedisStreamConfigProperties redisStreamConfigProperties;

    /**
     * stream 保留的最大消息数。
     * <p>
     * 本项目的失效广播是“从 stream 开头读”的（broadcast 模式），好处是重启后会把历史消息重放一遍，
     * 不会因为漏读而丢通知；代价是如果 stream 不封顶，内存会一直涨，每次重启也要重放全量。
     * 封一个足够大的上限：既约束内存与重放量，又给“短暂停摆的节点”留足补读窗口。
     * （本地缓存本身还有 {@code LocalCacheTtl} 的 TTL 上界兜底，所以就算真的超出窗口也不会长期旧。）
     */
    private static final long STREAM_MAX_LENGTH = 10000L;

    public RecordId push(String msg) {
        ObjectRecord<String, String> record = StreamRecords.newRecord()
                .in(redisStreamConfigProperties.getStreamName())   // 写进 stream（配置：invalid_program）
                .ofObject(msg)                                    // 消息体（节目ID字符串）
                .withId(RecordId.autoGenerate());                 // 自动生成消息ID（时间戳-序号）
        RecordId recordId = this.stringRedisTemplate.opsForStream().add(record);
        // 近似裁剪：XADD + XTRIM 两步，不用 pipeline。失效通知本身很少（运营改数据才发），
        // 多一次 Redis 往返换内存可控，值。
        Long trimmed = this.stringRedisTemplate.opsForStream()
                .trim(redisStreamConfigProperties.getStreamName(), STREAM_MAX_LENGTH, true);
        log.info("redis streamName : {} message : {} trimmed : {}",
                redisStreamConfigProperties.getStreamName(), msg, trimmed);
        return recordId;
    }
}
