package com.ticketflow.monitor;


import com.alibaba.fastjson2.JSON;
import com.alibaba.nacos.common.utils.StringUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * 钉钉消息通知。封装钉钉机器人消息推送，用于异常告警通知。
 */
@Slf4j
@RequiredArgsConstructor
public class DingTalkMessage {

    private final String token;

    private final RestTemplate restTemplate = new RestTemplate();

    private HttpEntity<String> createMessage(String message) {
        Map<String, Object> messageJson = new HashMap<>(8);
        Map<String, Object> context = new HashMap<>(8);
        context.put("content", message);
        messageJson.put("text", context);
        messageJson.put("msgtype", "text");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(JSON.toJSONString(messageJson), headers);
    }

    public void sendMessage(String message) {
        if (StringUtils.isNotEmpty(token)) {
            try {
                restTemplate.postForEntity(token, createMessage(message), Void.class);
            } catch (Exception e) {
                log.error("钉钉消息发送失败, message: {}", message, e);
            }
        }
    }
}
