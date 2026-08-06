package com.ticketflow.servicelock.aspect;

import com.ticketflow.lockinfo.LockInfoHandle;
import com.ticketflow.lockinfo.factory.LockInfoHandleFactory;
import com.ticketflow.servicelock.LockType;
import com.ticketflow.servicelock.ServiceLocker;
import com.ticketflow.servicelock.annotion.ServiceLock;
import com.ticketflow.servicelock.factory.ServiceLockFactory;
import com.ticketflow.servicelock.info.LockTimeOutStrategy;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServiceLockAspectTest {

    @Mock
    private LockInfoHandleFactory lockInfoHandleFactory;

    @Mock
    private ServiceLockFactory serviceLockFactory;

    @Test
    void lockTimeoutShouldFailFastWithoutExecutingBusinessMethod() throws Throwable {
        LockInfoHandle lockInfoHandle = mock(LockInfoHandle.class);
        when(lockInfoHandleFactory.getLockInfoHandle(anyString())).thenReturn(lockInfoHandle);
        when(lockInfoHandle.getLockName(any(), anyString(), any())).thenReturn("test-lock");

        ServiceLocker serviceLocker = mock(ServiceLocker.class);
        when(serviceLockFactory.getLock(LockType.Reentrant)).thenReturn(serviceLocker);
        when(serviceLocker.tryLock("test-lock", TimeUnit.SECONDS, 10)).thenReturn(false);

        ServiceLock serviceLock = mock(ServiceLock.class);
        when(serviceLock.lockType()).thenReturn(LockType.Reentrant);
        when(serviceLock.waitTime()).thenReturn(10L);
        when(serviceLock.timeUnit()).thenReturn(TimeUnit.SECONDS);
        when(serviceLock.customLockTimeoutStrategy()).thenReturn("");
        when(serviceLock.lockTimeoutStrategy()).thenReturn(LockTimeOutStrategy.FAIL);

        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);

        ServiceLockAspect aspect = new ServiceLockAspect(lockInfoHandleFactory, serviceLockFactory);

        assertThrows(RuntimeException.class, () -> aspect.around(joinPoint, serviceLock));
        verify(joinPoint, never()).proceed();
    }

    @Test
    void classLevelAnnotationShouldAcquireLockAndUnlock() throws Throwable {
        LockInfoHandle lockInfoHandle = mock(LockInfoHandle.class);
        when(lockInfoHandleFactory.getLockInfoHandle(anyString())).thenReturn(lockInfoHandle);
        when(lockInfoHandle.getLockName(any(), any(), any())).thenReturn("class-lock");

        ServiceLocker serviceLocker = mock(ServiceLocker.class);
        when(serviceLockFactory.getLock(LockType.Reentrant)).thenReturn(serviceLocker);
        when(serviceLocker.tryLock("class-lock", TimeUnit.SECONDS, 10)).thenReturn(true);

        ServiceLock serviceLock = mock(ServiceLock.class);
        when(serviceLock.lockType()).thenReturn(LockType.Reentrant);
        when(serviceLock.waitTime()).thenReturn(10L);
        when(serviceLock.timeUnit()).thenReturn(TimeUnit.SECONDS);

        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        Method bizMethod = ClassLevelTarget.class.getMethod("biz");
        when(signature.getMethod()).thenReturn(bizMethod);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getTarget()).thenReturn(new ClassLevelTarget());
        when(joinPoint.proceed()).thenReturn("ok");

        ServiceLockAspect aspect = new ServiceLockAspect(lockInfoHandleFactory, serviceLockFactory);

        assertEquals("ok", aspect.aroundClass(joinPoint, serviceLock));
        verify(serviceLocker).unlock("class-lock");
    }

    @Test
    void classLevelAnnotationShouldPassThroughWhenMethodAnnotated() throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        Method annotatedMethod = ClassLevelTarget.class.getMethod("methodAnnotated");
        when(signature.getMethod()).thenReturn(annotatedMethod);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.proceed()).thenReturn("ok");

        ServiceLockAspect aspect = new ServiceLockAspect(lockInfoHandleFactory, serviceLockFactory);

        assertEquals("ok", aspect.aroundClass(joinPoint, mock(ServiceLock.class)));
        verify(serviceLockFactory, never()).getLock(any());
        verify(joinPoint).proceed();
    }

    static class ClassLevelTarget {
        public String biz() {
            return "ok";
        }

        @ServiceLock(keys = {"#id"})
        public String methodAnnotated() {
            return "ok";
        }
    }
}
