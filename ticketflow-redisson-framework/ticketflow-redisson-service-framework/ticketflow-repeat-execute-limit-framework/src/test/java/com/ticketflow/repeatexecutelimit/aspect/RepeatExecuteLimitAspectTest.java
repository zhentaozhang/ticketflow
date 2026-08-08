package com.ticketflow.repeatexecutelimit.aspect;

import com.ticketflow.handle.RedissonDataHandle;
import com.ticketflow.locallock.LocalLockCache;
import com.ticketflow.lockinfo.LockInfoHandle;
import com.ticketflow.lockinfo.factory.LockInfoHandleFactory;
import com.ticketflow.repeatexecutelimit.annotion.RepeatExecuteLimit;
import com.ticketflow.servicelock.LockType;
import com.ticketflow.servicelock.ServiceLocker;
import com.ticketflow.servicelock.factory.ServiceLockFactory;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static com.ticketflow.repeatexecutelimit.constant.RepeatExecuteLimitConstant.PREFIX_NAME;
import static com.ticketflow.repeatexecutelimit.constant.RepeatExecuteLimitConstant.SUCCESS_FLAG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RepeatExecuteLimitAspectTest {

    @Mock
    private LocalLockCache localLockCache;

    @Mock
    private LockInfoHandleFactory lockInfoHandleFactory;

    @Mock
    private ServiceLockFactory serviceLockFactory;

    @Mock
    private RedissonDataHandle redissonDataHandle;

    private static final String LOCK_NAME = "order-create";

    private RepeatExecuteLimitAspect aspect() {
        return new RepeatExecuteLimitAspect(localLockCache, lockInfoHandleFactory, serviceLockFactory, redissonDataHandle);
    }

    private LockInfoHandle stubLockName() {
        LockInfoHandle lockInfoHandle = mock(LockInfoHandle.class);
        when(lockInfoHandleFactory.getLockInfoHandle(anyString())).thenReturn(lockInfoHandle);
        when(lockInfoHandle.getLockName(any(), anyString(), any())).thenReturn(LOCK_NAME);
        return lockInfoHandle;
    }

    private void stubLocalLockFree() {
        when(localLockCache.getLock(anyString(), anyBoolean())).thenReturn(new ReentrantLock());
    }

    private RepeatExecuteLimit stubRepeatLimit(long durationTime) {
        RepeatExecuteLimit repeatLimit = mock(RepeatExecuteLimit.class);
        when(repeatLimit.name()).thenReturn("order_create");
        when(repeatLimit.keys()).thenReturn(new String[]{"#orderId"});
        when(repeatLimit.durationTime()).thenReturn(durationTime);
        when(repeatLimit.message()).thenReturn("提交频繁，请稍后重试");
        return repeatLimit;
    }

    private ProceedingJoinPoint joinPointReturning(Object value) throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.proceed()).thenReturn(value);
        return joinPoint;
    }

    @Test
    void existingSuccessFlagShouldRejectBeforeAcquiringAnyLock() throws Throwable {
        stubLockName();
        when(redissonDataHandle.get(PREFIX_NAME + LOCK_NAME)).thenReturn(SUCCESS_FLAG);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        RepeatExecuteLimit repeatLimit = stubRepeatLimit(10L);

        assertThrows(RuntimeException.class, () -> aspect().around(joinPoint, repeatLimit));
        verify(localLockCache, never()).getLock(anyString(), anyBoolean());
        verify(joinPoint, never()).proceed();
    }

    @Test
    void localLockUnavailableShouldRejectWithoutDistributedLock() throws Throwable {
        stubLockName();
        when(redissonDataHandle.get(PREFIX_NAME + LOCK_NAME)).thenReturn(null);
        ReentrantLock occupiedLock = mock(ReentrantLock.class);
        when(occupiedLock.tryLock()).thenReturn(false);
        when(localLockCache.getLock(anyString(), anyBoolean())).thenReturn(occupiedLock);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        RepeatExecuteLimit repeatLimit = stubRepeatLimit(10L);

        assertThrows(RuntimeException.class, () -> aspect().around(joinPoint, repeatLimit));
        verify(serviceLockFactory, never()).getLock(any());
        verify(joinPoint, never()).proceed();
    }

    @Test
    void distributedLockUnavailableShouldRejectWithoutExecuting() throws Throwable {
        stubLockName();
        when(redissonDataHandle.get(PREFIX_NAME + LOCK_NAME)).thenReturn(null);
        stubLocalLockFree();
        ServiceLocker serviceLocker = mock(ServiceLocker.class);
        when(serviceLockFactory.getLock(LockType.Reentrant)).thenReturn(serviceLocker);
        when(serviceLocker.tryLock(LOCK_NAME, TimeUnit.SECONDS, 0)).thenReturn(false);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        RepeatExecuteLimit repeatLimit = stubRepeatLimit(10L);

        assertThrows(RuntimeException.class, () -> aspect().around(joinPoint, repeatLimit));
        verify(joinPoint, never()).proceed();
    }

    @Test
    void successFlagSetBetweenLocksShouldRejectInsideDistributedLock() throws Throwable {
        stubLockName();
        when(redissonDataHandle.get(PREFIX_NAME + LOCK_NAME)).thenReturn(null, SUCCESS_FLAG);
        stubLocalLockFree();
        ServiceLocker serviceLocker = mock(ServiceLocker.class);
        when(serviceLockFactory.getLock(LockType.Reentrant)).thenReturn(serviceLocker);
        when(serviceLocker.tryLock(LOCK_NAME, TimeUnit.SECONDS, 0)).thenReturn(true);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        RepeatExecuteLimit repeatLimit = stubRepeatLimit(10L);

        assertThrows(RuntimeException.class, () -> aspect().around(joinPoint, repeatLimit));
        verify(joinPoint, never()).proceed();
        verify(serviceLocker).unlock(LOCK_NAME);
    }

    @Test
    void successShouldWriteIdempotentFlagWhenDurationPositive() throws Throwable {
        stubLockName();
        when(redissonDataHandle.get(PREFIX_NAME + LOCK_NAME)).thenReturn(null);
        stubLocalLockFree();
        ServiceLocker serviceLocker = mock(ServiceLocker.class);
        when(serviceLockFactory.getLock(LockType.Reentrant)).thenReturn(serviceLocker);
        when(serviceLocker.tryLock(LOCK_NAME, TimeUnit.SECONDS, 0)).thenReturn(true);
        ProceedingJoinPoint joinPoint = joinPointReturning("ok");
        RepeatExecuteLimit repeatLimit = stubRepeatLimit(10L);

        assertEquals("ok", aspect().around(joinPoint, repeatLimit));
        verify(redissonDataHandle).set(PREFIX_NAME + LOCK_NAME, SUCCESS_FLAG, 10L, TimeUnit.SECONDS);
        verify(serviceLocker).unlock(LOCK_NAME);
    }

    @Test
    void successShouldNotWriteIdempotentFlagWhenDurationZero() throws Throwable {
        stubLockName();
        when(redissonDataHandle.get(PREFIX_NAME + LOCK_NAME)).thenReturn(null);
        stubLocalLockFree();
        ServiceLocker serviceLocker = mock(ServiceLocker.class);
        when(serviceLockFactory.getLock(LockType.Reentrant)).thenReturn(serviceLocker);
        when(serviceLocker.tryLock(LOCK_NAME, TimeUnit.SECONDS, 0)).thenReturn(true);
        ProceedingJoinPoint joinPoint = joinPointReturning("ok");
        RepeatExecuteLimit repeatLimit = stubRepeatLimit(0L);

        assertEquals("ok", aspect().around(joinPoint, repeatLimit));
        verify(redissonDataHandle, never()).set(anyString(), anyString(), anyLong(), any());
        verify(serviceLocker).unlock(LOCK_NAME);
    }

    @Test
    void classLevelAnnotationShouldPassThroughWhenMethodAnnotated() throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        Method annotatedMethod = ClassLevelTarget.class.getMethod("methodAnnotated");
        when(signature.getMethod()).thenReturn(annotatedMethod);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.proceed()).thenReturn("ok");

        assertEquals("ok", aspect().aroundClass(joinPoint, mock(RepeatExecuteLimit.class)));
        verify(serviceLockFactory, never()).getLock(any());
        verify(redissonDataHandle, never()).get(anyString());
    }

    @Test
    void classLevelAnnotationShouldApplyLimitWhenMethodNotAnnotated() throws Throwable {
        stubLockName();
        when(redissonDataHandle.get(PREFIX_NAME + LOCK_NAME)).thenReturn(null);
        stubLocalLockFree();
        ServiceLocker serviceLocker = mock(ServiceLocker.class);
        when(serviceLockFactory.getLock(LockType.Reentrant)).thenReturn(serviceLocker);
        when(serviceLocker.tryLock(LOCK_NAME, TimeUnit.SECONDS, 0)).thenReturn(true);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        Method bizMethod = ClassLevelTarget.class.getMethod("biz");
        when(signature.getMethod()).thenReturn(bizMethod);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getTarget()).thenReturn(new ClassLevelTarget());
        when(joinPoint.proceed()).thenReturn("ok");
        RepeatExecuteLimit repeatLimit = stubRepeatLimit(0L);

        assertEquals("ok", aspect().aroundClass(joinPoint, repeatLimit));
        verify(redissonDataHandle, times(2)).get(PREFIX_NAME + LOCK_NAME);
        verify(serviceLocker).unlock(LOCK_NAME);
    }

    static class ClassLevelTarget {
        public String biz() {
            return "ok";
        }

        @RepeatExecuteLimit(keys = {"#orderId"})
        public String methodAnnotated() {
            return "ok";
        }
    }
}
