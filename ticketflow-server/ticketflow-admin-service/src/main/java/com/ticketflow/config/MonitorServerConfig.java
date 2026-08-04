package com.ticketflow.config;

import com.ticketflow.monitor.DingTalkMessage;
import com.ticketflow.monitor.MonitorServer;
import de.codecentric.boot.admin.server.domain.entities.InstanceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 监控服务配置。配置监控服务器的端口、线程池和可选组件。
 */
@Configuration
public class MonitorServerConfig {

    @Value("${dingtalk.token:}")
    private String token;

    @Bean
    public DingTalkMessage dingTalkMessage() {
        return new DingTalkMessage(token);
    }

    @Bean
    public MonitorServer monitorServer(DingTalkMessage dingTalkMessage, InstanceRepository repository) {
        return new MonitorServer(dingTalkMessage, repository);
    }


}
