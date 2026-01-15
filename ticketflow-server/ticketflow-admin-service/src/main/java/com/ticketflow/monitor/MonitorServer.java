package com.ticketflow.monitor;

import de.codecentric.boot.admin.server.domain.entities.Instance;
import de.codecentric.boot.admin.server.domain.entities.InstanceRepository;
import de.codecentric.boot.admin.server.domain.events.InstanceEvent;
import de.codecentric.boot.admin.server.domain.events.InstanceStatusChangedEvent;
import de.codecentric.boot.admin.server.notify.AbstractStatusChangeNotifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.expression.MapAccessor;
import org.springframework.expression.Expression;
import org.springframework.expression.ParserContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Spring Boot Admin 微服务状态变更通知器。
 * 当注册的微服务实例状态变化（UP/DOWN/OFFLINE/UNKNOWN）时，
 * 通过 SpEL 模板拼装告警消息并发送钉钉通知。
 */
@Slf4j
public class MonitorServer extends AbstractStatusChangeNotifier {
    
    private DingTalkMessage dingTalkMessage;
    
    // SpEL 模板：变量来自 doNotify 中 root Map — #{instance.registration.name} #{fromStatus} #{toStatus} 等
    private Expression text = new SpelExpressionParser()
            .parseExpression("#{instance.registration.name} (#{instance.id}) status changed from #{fromStatus} to #{toStatus}  #{instance.registration.healthUrl}", ParserContext.TEMPLATE_EXPRESSION);
    
    // 忽略列表：from:to 格式，也支持通配符 *:UP（任何状态→UP）、from:*（from→任何状态）
    private String[] ignoreStatusChanges = new String[]{"UNKNOWN:UP","DOWN:UP","OFFLINE:UP"};
    
    public MonitorServer(DingTalkMessage dingTalkMessage, InstanceRepository repository) {
        super(repository);
        this.setIgnoreChanges(ignoreStatusChanges);
        this.dingTalkMessage = dingTalkMessage;
    }
    
    @Override
    protected boolean shouldNotify(InstanceEvent event, Instance instance) {
        if (event instanceof InstanceStatusChangedEvent) {
            InstanceStatusChangedEvent statusChange = (InstanceStatusChangedEvent)event;
            String from = this.getLastStatus(event.getInstance());
            String to = statusChange.getStatusInfo().getStatus();
            // 三组 binarySearch 分别匹配精确 from:to、通配 *:to、通配 from:*，任一匹配则跳过通知
            return Arrays.binarySearch(this.ignoreStatusChanges, from + ":" + to) < 0
                    && Arrays.binarySearch(this.ignoreStatusChanges, "*:" + to) < 0
                    && Arrays.binarySearch(this.ignoreStatusChanges, from + ":*") < 0;
        } else {
            return false;
        }
    }
    
    @Override
    protected Mono<Void> doNotify(final InstanceEvent event, final Instance instance) {
        InstanceStatusChangedEvent statusChange = (InstanceStatusChangedEvent)event;
        String from = this.getLastStatus(event.getInstance());
        String to = statusChange.getStatusInfo().getStatus();
        
        // 构建 SpEL 上下文：添加 MapAccessor 使 SpEL 能访问 Map 中的对象属性（如 instance.registration.name）
        Map<String, Object> root = new HashMap<>(16);
        root.put("instance", instance);
        root.put("fromStatus",from);
        root.put("toStatus",to);
        StandardEvaluationContext context = new StandardEvaluationContext(root);
        context.addPropertyAccessor(new MapAccessor());
        String message = text.getValue(context, String.class);
        return Mono.fromRunnable(() -> {
            log.info("Mono.fromRunnable执行 message:{}",message);
            dingTalkMessage.sendMessage(message);
        });
    }
}
