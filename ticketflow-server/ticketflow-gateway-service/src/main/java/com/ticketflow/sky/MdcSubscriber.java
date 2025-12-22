package com.ticketflow.sky;

import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Subscription;
import org.slf4j.MDC;
import reactor.core.CoreSubscriber;
import reactor.util.context.Context;

import java.lang.reflect.Field;
import java.util.Optional;

import static com.ticketflow.constant.Constant.SKY_WALKING_TRACE_ID;
import static com.ticketflow.constant.Constant.TRACE_ID;

/**
 * Reactor 钩子，在响应式网关的 onNext 中将 SkyWalking 链路追踪 ID 写入 SLF4J MDC，
 * 确保下游业务线程的日志也包含 trace ID，实现分布式链路追踪。
 */
@Slf4j
public class MdcSubscriber implements CoreSubscriber {


    private static final String SKYWALKING_CTX_SNAPSHOT = "SKYWALKING_CONTEXT_SNAPSHOT";

    private final CoreSubscriber<Object> actual;

    public MdcSubscriber(CoreSubscriber<Object> actual) {
        this.actual = actual;
    }

    @Override
    public void onSubscribe(Subscription s) {
        actual.onSubscribe(s);
    }

    @Override
    public void onNext(Object o) {
        Context c = actual.currentContext();
        Optional<String> traceIdOptional = Optional.empty();
        if (!c.isEmpty() && c.hasKey(SKYWALKING_CTX_SNAPSHOT)) {
            Object object = c.get(SKYWALKING_CTX_SNAPSHOT);
            // SkyWalking 代理通过字节码注入注入 TraceSegment，其内部 traceId 字段是 inst 对象而非 String，
            // 必须通过反射读取后 JSON 序列化再反序列化才能拿到纯 id 字符串
            Object traceId = findField(object, TRACE_ID);
            String ids = JSON.toJSONString(traceId);
            traceIdOptional = Optional.ofNullable(ids)
                    .map(JSON::parseObject)
                    .map(t -> t.get("id"))
                    .map(Object::toString);
        }

        MDC.put(SKY_WALKING_TRACE_ID, traceIdOptional.orElse("N/A"));
        actual.onNext(o);
    }

    @Override
    public void onError(Throwable throwable) {
        actual.onError(throwable);
    }

    @Override
    public void onComplete() {
        actual.onComplete();
    }

    @Override
    public Context currentContext() {
        return actual.currentContext();
    }
    
    // SkyWalking 注入的类不是普通 POJO，只能通过反射读取内部字段
    private static Object findField(Object object, String field) {
        if (object == null) {
            return null;
        }
        try {
            Class<?> aClass = object.getClass();
            
            Field mValuesField = null;
            mValuesField = aClass.getDeclaredField(field);
            mValuesField.setAccessible(true);
            return mValuesField.get(object);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}