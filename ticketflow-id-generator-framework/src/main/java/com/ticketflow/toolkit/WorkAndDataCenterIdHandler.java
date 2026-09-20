package com.ticketflow.toolkit;

import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * WorkId/DataCenterId处理器。使用Redis+Lua脚本生成和管理雪花算法的数据中心ID和机器ID。
 */
@Slf4j
public class WorkAndDataCenterIdHandler {

    private final String SNOWFLAKE_WORK_ID_KEY = "snowflake_work_id";

    private final String SNOWFLAKE_DATA_CENTER_ID_key = "snowflake_data_center_id";


    public final List<String> keys = Arrays.asList(SNOWFLAKE_WORK_ID_KEY, SNOWFLAKE_DATA_CENTER_ID_key);

    private final StringRedisTemplate stringRedisTemplate;

    private DefaultRedisScript<String> redisScript;

    public WorkAndDataCenterIdHandler(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        try {
            redisScript = new DefaultRedisScript<>();
            redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/workAndDataCenterId.lua")));
            redisScript.setResultType(String.class);
        } catch (Exception e) {
            log.error("redisScript init lua error", e);
        }
    }

    public WorkDataCenterId getWorkAndDataCenterId() {
        WorkDataCenterId workDataCenterId = new WorkDataCenterId();
        try {
            Object[] data = new String[2];
            data[0] = String.valueOf(IdGeneratorConstant.MAX_WORKER_ID);
            data[1] = String.valueOf(IdGeneratorConstant.MAX_DATA_CENTER_ID);
            String result = stringRedisTemplate.execute(redisScript, keys, data);
            // 仅当结果有效时才覆盖占位对象：result 为 null/空 时 JSON.parseObject 返回 null，
            // 会把 workDataCenterId 覆盖成 null，后续 SnowflakeIdGenerator 构造时 NPE。
            if (Objects.nonNull(result) && !result.isBlank()) {
                WorkDataCenterId parsed = JSON.parseObject(result, WorkDataCenterId.class);
                if (Objects.nonNull(parsed)) {
                    workDataCenterId = parsed;
                }
            }
        } catch (Exception e) {
            log.error("getWorkAndDataCenterId error", e);
        }
        return workDataCenterId;
    }
}
