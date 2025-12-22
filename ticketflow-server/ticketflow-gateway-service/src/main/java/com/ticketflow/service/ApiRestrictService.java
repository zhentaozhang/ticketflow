package com.ticketflow.service;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.date.DateUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.baidu.fsg.uid.UidGenerator;
import com.ticketflow.core.RedisKeyManage;
import com.ticketflow.util.StringUtil;
import com.ticketflow.dto.ApiDataDto;
import com.ticketflow.enums.ApiRuleType;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.enums.RuleTimeUnit;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.kafka.ApiDataMessageSend;
import com.ticketflow.property.GatewayProperty;
import com.ticketflow.redis.RedisCache;
import com.ticketflow.redis.RedisKeyBuild;
import com.ticketflow.service.lua.ApiRestrictCacheOperate;
import com.ticketflow.util.DateUtils;
import com.ticketflow.vo.DepthRuleVo;
import com.ticketflow.vo.RuleVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * API 限流核心服务，由 RequestValidationFilter 在每个请求中调用。
 * 支持两级限流规则，通过 Lua 脚本在 Redis 中原子执行：
 * <p>
 * 一级（普通规则）：
 * - statTime 窗口内请求数 > threshold → 写入 ruleLimitKey + TTL，触发限流
 * 数据结构：counter (incrby) + 过期自清理
 * <p>
 * 二级（深度规则）：
 * - 在一级基础上增加时间窗口（startTimeWindow ~ endTimeWindow）和滑动 ZSet 统计
 * - ZSet member = "currentTime_count"，按时间戳范围 zcount 精准计数
 * - 同一窗口内可设置多层递进阈值
 * <p>
 * 触发回调：首次触发时通过 Kafka (ApiDataMessageSend) 发送告警消息
 */
@Slf4j
@Component
public class ApiRestrictService {

    /**
     * Redis操作类
     * 用于读取限流规则配置，以及执行限流过程中需要使用的Redis数据
     */
    @Autowired
    private RedisCache redisCache;

    /**
     * 网关配置
     * 保存需要进行限流的API路径配置
     */
    @Autowired
    private GatewayProperty gatewayProperty;

    /**
     * Kafka消息发送
     * 当API触发限流时，将限流记录发送到Kafka进行异步处理
     */
    @Autowired(required = false)
    private ApiDataMessageSend apiDataMessageSend;

    /**
     * 限流核心执行类
     * 内部通过Lua脚本操作Redis，保证限流判断和计数的原子性
     */
    @Autowired
    private ApiRestrictCacheOperate apiRestrictCacheOperate;

    /**
     * 百度Uid生成器
     * 用于生成限流记录的唯一ID
     */
    @Autowired
    private UidGenerator uidGenerator;

    /**
     * 判断当前请求URL是否需要进行限流
     * <p>
     * 原理：
     * 1. 从配置文件中获取需要限流的路径列表
     * 2. 使用AntPathMatcher进行路径匹配
     * 3. 匹配成功说明该接口需要进入限流逻辑
     */
    public boolean checkApiRestrict(String requestUri) {
        if (gatewayProperty.getApiRestrictPaths() != null) {

            for (String apiRestrictPath : gatewayProperty.getApiRestrictPaths()) {

                // Spring提供的路径匹配器
                // 支持通配符，例如：
                // /user/** 可以匹配 /user/login、/user/info等路径
                PathMatcher matcher = new AntPathMatcher();

                if (matcher.match(apiRestrictPath, requestUri)) {
                    return true;
                }
            }
        }

        return false;
    }

    public void apiRestrict(String id, String url, ServerHttpRequest request) {
        // 请求的路径在配置范围内的话
        if (checkApiRestrict(url)) {
            // 是否触发限流：0不触发，1触发
            long triggerResult = 0L;
            // 触发的规则类型：普通规则/深度规则
            long triggerCallStat = 0L;
            // 当前请求次数
            long apiCount;
            // 限流阈值
            long threshold;
            // 触发的深度规则索引
            long messageIndex;
            // 限流提示信息
            String message = "";
            // 获得请求客户端地址
            String ip = getIpAddress(request);

            // 构建限流 key: ip_{userId}_{url}，按 IP+用户+URL 精准限流
            StringBuilder stringBuilder = new StringBuilder(ip);
            if (StringUtil.isNotEmpty(id)) {
                stringBuilder.append("_").append(id);
            }
            // commonKey = 谁（IP/用户） + 访问什么（URL），作为 Redis 限流统计的唯一标识
            String commonKey = stringBuilder.append("_").append(url).toString();

            try {
                // 保存深度限流规则
                List<DepthRuleVo> depthRuleVoList = new ArrayList<>();

                // 从 Redis Hash 中获取普通限流规则
                RuleVo ruleVo = redisCache.getForHash(
                        RedisKeyBuild.createRedisKey(RedisKeyManage.ALL_RULE_HASH),
                        RedisKeyBuild.createRedisKey(RedisKeyManage.RULE).getRelKey(),
                        RuleVo.class);

                // 从 Redis Hash 中获取深度限流规则(JSON字符串)
                String depthRuleStr = redisCache.getForHash(
                        RedisKeyBuild.createRedisKey(RedisKeyManage.ALL_RULE_HASH),
                        RedisKeyBuild.createRedisKey(RedisKeyManage.DEPTH_RULE).getRelKey(),
                        String.class);

                // 将深度规则 JSON 转换为规则对象列表
                if (StringUtil.isNotEmpty(depthRuleStr)) {
                    depthRuleVoList = JSON.parseArray(depthRuleStr, DepthRuleVo.class);
                }

                // 默认
                // ↓
                //没有规则
                // ↓
                //有 RuleVo
                // ↓
                //普通限流
                // ↓
                //有 RuleVo + DepthRuleVo
                // ↓
                //深度限流（优先级最高）

                // 判断当前接口使用的限流规则类型
                // 默认：无规则
                int apiRuleType = ApiRuleType.NO_RULE.getCode();

                // 存在普通限流规则
                // 使用普通限流规则
                if (Optional.ofNullable(ruleVo).isPresent()) {
                    apiRuleType = ApiRuleType.RULE.getCode();
                    // 保存普通规则提示信息
                    message = ruleVo.getMessage();
                }

                // 同时存在普通规则和深度规则
                // 优先使用深度限流规则
                if (Optional.ofNullable(ruleVo).isPresent() && CollectionUtil.isNotEmpty(depthRuleVoList)) {
                    apiRuleType = ApiRuleType.DEPTH_RULE.getCode();
                }

                // 存在限流规则，执行限流校验
                if (apiRuleType == ApiRuleType.RULE.getCode() || apiRuleType == ApiRuleType.DEPTH_RULE.getCode()) {
                    assert ruleVo != null;
                    // 构建普通限流规则参数
                    JSONObject parameter = getRuleParameter(apiRuleType, commonKey, ruleVo);
                    // 如果是深度限流，追加深度规则参数
                    if (apiRuleType == ApiRuleType.DEPTH_RULE.getCode()) {
                        parameter = getDepthRuleParameter(parameter, commonKey, depthRuleVoList);
                    }
                    // 调用 Lua 脚本执行 Redis 限流判断
                    ApiRestrictData apiRestrictData = apiRestrictCacheOperate
                            .apiRuleOperate(Collections.singletonList(JSON.toJSONString(parameter)), new Object[]{});
                    // 获取限流结果
                    triggerResult = apiRestrictData.getTriggerResult();
                    // 获取触发规则类型
                    triggerCallStat = apiRestrictData.getTriggerCallStat();
                    // 获取当前请求次数
                    apiCount = apiRestrictData.getApiCount();
                    // 获取限流阈值
                    threshold = apiRestrictData.getThreshold();
                    // 获取触发的深度规则索引
                    messageIndex = apiRestrictData.getMessageIndex();
                    // 根据深度规则索引获取对应提示信息
                    if (messageIndex != -1) {
                        message = Optional.ofNullable(depthRuleVoList.get((int) messageIndex))
                                .map(DepthRuleVo::getMessage)
                                .filter(StringUtil::isNotEmpty)
                                .orElse(message);
                    }
                    // 打印限流执行结果
                    log.info(
                            "api rule [key : {}], [triggerResult : {}], [triggerCallStat : {}], [apiCount : {}], [threshold : {}]",
                            commonKey,
                            triggerResult,
                            triggerCallStat,
                            apiCount,
                            threshold
                    );
                }

            } catch (Exception e) {
                log.error("redis Lua eror", e);
            }

            // 如果触发限流规则，则拒绝当前请求
            if (triggerResult == 1) {
                // 记录限流触发信息，用于统计和告警
                if (triggerCallStat == ApiRuleType.RULE.getCode() || triggerCallStat == ApiRuleType.DEPTH_RULE.getCode()) {
                    saveApiData(request, url, (int) triggerCallStat);
                }
                // 默认限流提示信息
                String defaultMessage = BaseCode.API_RULE_TRIGGER.getMsg();
                // 如果配置了自定义提示语，则使用自定义提示
                if (StringUtil.isNotEmpty(message)) {
                    defaultMessage = message;
                }
                // 抛出限流异常，阻止请求继续执行
                throw new TicketFlowFrameException(BaseCode.API_RULE_TRIGGER.getCode(), defaultMessage);
            }
        }
    }

    public JSONObject getRuleParameter(int apiRuleType, String commonKey, RuleVo ruleVo) {
        // 构建传递给 Redis Lua 脚本的限流参数
        JSONObject parameter = new JSONObject();
        // 限流规则类型：普通规则/深度规则
        parameter.put("apiRuleType", apiRuleType);
        // 当前接口限流唯一标识
        String ruleKey = "rule_api_limit" + "_" + commonKey;
        parameter.put("ruleKey", ruleKey);
        // 统计时间窗口，例如 10 秒内最多访问 100 次
        // 如果配置单位是分钟，则转换成秒
        parameter.put("statTime", String.valueOf(
                Objects.equals(ruleVo.getStatTimeType(), RuleTimeUnit.SECOND.getCode())
                        ? ruleVo.getStatTime()
                        : ruleVo.getStatTime() * 60
        ));
        // 限流阈值，例如窗口内最大请求次数
        parameter.put("threshold", ruleVo.getThreshold());
        // 触发限流后的生效时间，例如限制 60 秒
        // 如果配置单位是分钟，则转换成秒
        parameter.put("effectiveTime", String.valueOf(
                Objects.equals(ruleVo.getEffectiveTimeType(), RuleTimeUnit.SECOND.getCode())
                        ? ruleVo.getEffectiveTime()
                        : ruleVo.getEffectiveTime() * 60
        ));
        // Redis 中记录触发限流状态的 key
        parameter.put("ruleLimitKey",
                RedisKeyBuild.createRedisKey(RedisKeyManage.RULE_LIMIT, commonKey).getRelKey());
        // Redis ZSet 统计请求次数使用的 key
        parameter.put("zSetRuleStatKey",
                RedisKeyBuild.createRedisKey(RedisKeyManage.Z_SET_RULE_STAT, commonKey).getRelKey());
        return parameter;
    }

    public JSONObject getDepthRuleParameter(JSONObject parameter, String commonKey, List<DepthRuleVo> depthRuleVoList) {
        // 按开始时间排序，保证深度规则执行顺序
        depthRuleVoList = sortStartTimeWindow(depthRuleVoList);
        // 深度规则数量
        parameter.put("depthRuleSize", String.valueOf(depthRuleVoList.size()));
        // 当前时间，用于判断是否命中时间窗口
        parameter.put("currentTime", System.currentTimeMillis());
        // 保存每一层深度限流规则
        List<JSONObject> depthRules = new ArrayList<>();
        for (int i = 0; i < depthRuleVoList.size(); i++) {
            JSONObject depthRule = new JSONObject();
            // 当前层级规则
            DepthRuleVo depthRuleVo = depthRuleVoList.get(i);
            // 统计窗口，例如10秒内统计请求次数
            depthRule.put("statTime",
                    Objects.equals(depthRuleVo.getStatTimeType(), RuleTimeUnit.SECOND.getCode())
                            ? depthRuleVo.getStatTime()
                            : depthRuleVo.getStatTime() * 60);
            // 当前时间窗口允许的最大请求数
            depthRule.put("threshold", depthRuleVo.getThreshold());
            // 触发限制后的持续时间
            depthRule.put("effectiveTime",
                    String.valueOf(
                            Objects.equals(depthRuleVo.getEffectiveTimeType(), RuleTimeUnit.SECOND.getCode())
                                    ? depthRuleVo.getEffectiveTime()
                                    : depthRuleVo.getEffectiveTime() * 60
                    ));
            // 当前深度规则对应的 Redis 限流标记 Key
            depthRule.put("depthRuleLimit",
                    RedisKeyBuild.createRedisKey(
                            RedisKeyManage.DEPTH_RULE_LIMIT,
                            i,
                            commonKey
                    ).getRelKey());
            // 当前规则生效的时间范围
            depthRule.put("startTimeWindowTimestamp",
                    depthRuleVo.getStartTimeWindowTimestamp());
            depthRule.put("endTimeWindowTimestamp",
                    depthRuleVo.getEndTimeWindowTimestamp());
            // 添加当前层规则
            depthRules.add(depthRule);
        }
        // 将所有深度规则放入参数，交给 Lua 执行
        parameter.put("depthRules", depthRules);
        return parameter;
    }

    public List<DepthRuleVo> sortStartTimeWindow(List<DepthRuleVo> depthRuleVoList) {
        // 遍历规则，补充开始时间和结束时间对应的时间戳
        return depthRuleVoList.stream()
                .peek(depthRuleVo -> {
                    // 将开始时间窗口转换成当天对应时间戳
                    depthRuleVo.setStartTimeWindowTimestamp(
                            getTimeWindowTimestamp(depthRuleVo.getStartTimeWindow()));
                    // 将结束时间窗口转换成当天对应时间戳
                    depthRuleVo.setEndTimeWindowTimestamp(
                            getTimeWindowTimestamp(depthRuleVo.getEndTimeWindow()));
                })
                // 按规则开始时间升序排列
                .sorted(Comparator.comparing(DepthRuleVo::getStartTimeWindowTimestamp))
                // 转换成 List 返回
                .collect(Collectors.toList());
    }

    public long getTimeWindowTimestamp(String timeWindow) {
        String today = DateUtil.today();
        return DateUtil.parse(today + " " + timeWindow).getTime();
    }

    /**
     * 获取请求的归属IP地址
     *
     * @param request 请求
     */
    public static String getIpAddress(ServerHttpRequest request) {
        String unknown = "unknown";
        String split = ",";
        HttpHeaders headers = request.getHeaders();
        String ip = headers.getFirst("x-forwarded-for");
        if (ip != null && ip.length() != 0 && !unknown.equalsIgnoreCase(ip)) {
            // 多次反向代理后会有多个ip值，第一个ip才是真实ip
            if (ip.contains(split)) {
                ip = ip.split(split)[0];
            }
        }
        if (ip == null || ip.length() == 0 || unknown.equalsIgnoreCase(ip)) {
            ip = headers.getFirst("Proxy-Client-IP");
        }
        if (ip == null || ip.length() == 0 || unknown.equalsIgnoreCase(ip)) {
            ip = headers.getFirst("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.length() == 0 || unknown.equalsIgnoreCase(ip)) {
            ip = headers.getFirst("HTTP_CLIENT_IP");
        }
        if (ip == null || ip.length() == 0 || unknown.equalsIgnoreCase(ip)) {
            ip = headers.getFirst("HTTP_X_FORWARDED_FOR");
        }
        if (ip == null || ip.length() == 0 || unknown.equalsIgnoreCase(ip)) {
            ip = headers.getFirst("X-Real-IP");
        }
        if (ip == null || ip.length() == 0 || unknown.equalsIgnoreCase(ip)) {
            ip = Objects.requireNonNull(request.getRemoteAddress()).getAddress().getHostAddress();
        }
        return ip;
    }

    public void saveApiData(ServerHttpRequest request, String apiUrl, Integer type) {
        // 创建 API 限流记录对象
        ApiDataDto apiDataDto = new ApiDataDto();
        // 生成唯一记录ID
        apiDataDto.setId(uidGenerator.getUid());
        // 记录触发限流的客户端IP
        apiDataDto.setApiAddress(getIpAddress(request));
        // 记录触发限流的接口地址
        apiDataDto.setApiUrl(apiUrl);
        // 记录限流发生时间
        apiDataDto.setCreateTime(DateUtils.now());

        // 按日期、小时、分钟、秒拆分时间，方便后续统计查询
        apiDataDto.setCallDayTime(DateUtils.nowStr(DateUtils.FORMAT_DATE));
        apiDataDto.setCallHourTime(DateUtils.nowStr(DateUtils.FORMAT_HOUR));
        apiDataDto.setCallMinuteTime(DateUtils.nowStr(DateUtils.FORMAT_MINUTE));
        apiDataDto.setCallSecondTime(DateUtils.nowStr(DateUtils.FORMAT_SECOND));

        // 记录触发的规则类型：普通规则/深度规则
        apiDataDto.setType(type);
        // 发送 Kafka 消息，异步处理限流记录
        Optional.ofNullable(apiDataMessageSend)
                .ifPresent(send -> send.sendMessage(JSON.toJSONString(apiDataDto)));
    }
}
