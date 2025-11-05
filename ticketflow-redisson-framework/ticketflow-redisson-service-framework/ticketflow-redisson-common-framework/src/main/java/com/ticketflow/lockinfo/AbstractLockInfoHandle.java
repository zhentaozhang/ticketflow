package com.ticketflow.lockinfo;


import com.ticketflow.core.SpringUtil;
import com.ticketflow.parser.ExtParameterNameDiscoverer;
import com.ticketflow.util.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.util.ObjectUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static com.ticketflow.core.Constants.SEPARATOR;

/**
 * 锁名构造器的抽象基类。
 *
 * 最终生成的 Redis 锁 key 格式：
 *   {prefixDistinctionName}-{getLockPrefixName()}:{name}:{SpEL 解析后的 key 值}
 *   示例：default-SERVICE_LOCK:PROGRAM_ORDER_CREATE_V1:123
 *
 * getLockName()      — 从 @ServiceLock/@RepeatExecuteLimit 注解 + SpEL 动态构建（给切面用）
 * simpleGetLockName() — 直接拼接字符串（给 ServiceLockTool 手动调用用）
 *
 * SpEL 解析（getSpElKey）：将注解中的 "#programId" 等表达式，根据方法参数名和入参值求值。
 * 使用 ExtParameterNameDiscoverer 获取参数名（兼容 Java 8 -parameters 和无 -parameters 编译）。
 *
 * 两个子类通过 getLockPrefixName() 使用不同的前缀，防止锁名冲突：
 *   ServiceLockHandle              → "SERVICE_LOCK"
 *   RepeatExecuteLimitLockInfoHandle → "REPEAT_EXECUTE_LIMIT"
 **/
@Slf4j
public abstract class AbstractLockInfoHandle implements LockInfoHandle {
    
    private static final String LOCK_DISTRIBUTE_ID_NAME_PREFIX = "LOCK_DISTRIBUTE_ID";

    private final ParameterNameDiscoverer nameDiscoverer = new ExtParameterNameDiscoverer();

    private final ExpressionParser parser = new SpelExpressionParser();
    
    /**
     * 锁信息前缀
     * @return 具体前缀
     * */
    protected abstract String getLockPrefixName();
    @Override
    public String getLockName(JoinPoint joinPoint,String name,String[] keys){
        return SpringUtil.getPrefixDistinctionName() + "-" + getLockPrefixName() + SEPARATOR + name + getRelKey(joinPoint, keys);
    }
    @Override
    public String simpleGetLockName(String name,String[] keys){
        List<String> definitionKeyList = new ArrayList<>();
        for (String key : keys) {
            if (StringUtil.isNotEmpty(key)) {
                definitionKeyList.add(key);
            }
        }
        return SpringUtil.getPrefixDistinctionName() + "-" + 
                LOCK_DISTRIBUTE_ID_NAME_PREFIX + SEPARATOR + name + SEPARATOR + String.join(SEPARATOR, definitionKeyList);
    }

    /**
     * 获取自定义键
     * */
    private String getRelKey(JoinPoint joinPoint, String[] keys){
        Method method = getMethod(joinPoint);
        List<String> definitionKeys = getSpElKey(keys, method, joinPoint.getArgs());
        return SEPARATOR + String.join(SEPARATOR, definitionKeys);
    }
    
    private Method getMethod(JoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        if (method.getDeclaringClass().isInterface()) {
            try {
                method = joinPoint.getTarget().getClass().getDeclaredMethod(signature.getName(),
                        method.getParameterTypes());
            } catch (Exception e) {
                log.error("get method error ",e);
            }
        }
        return method;
    }

    private List<String> getSpElKey(String[] definitionKeys, Method method, Object[] parameterValues) {
        List<String> definitionKeyList = new ArrayList<>();
        for (String definitionKey : definitionKeys) {
            if (!ObjectUtils.isEmpty(definitionKey)) {
                EvaluationContext context = new MethodBasedEvaluationContext(null, method, parameterValues, nameDiscoverer);
                Object objKey = parser.parseExpression(definitionKey).getValue(context);
                definitionKeyList.add(ObjectUtils.nullSafeToString(objKey));
            }
        }
        return definitionKeyList;
    }

}
