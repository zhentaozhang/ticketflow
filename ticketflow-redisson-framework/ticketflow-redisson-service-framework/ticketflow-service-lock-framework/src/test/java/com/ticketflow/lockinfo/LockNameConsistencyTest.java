package com.ticketflow.lockinfo;

import com.ticketflow.core.SpringUtil;
import com.ticketflow.lockinfo.impl.ServiceLockInfoHandle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import static com.ticketflow.constant.Constant.DEFAULT_PREFIX_DISTINCTION_NAME;
import static com.ticketflow.constant.Constant.PREFIX_DISTINCTION_NAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LockNameConsistencyTest {

    private static final String SERVICE_LOCK_PREFIX = "SERVICE_LOCK";

    @Mock
    private ConfigurableApplicationContext applicationContext;

    @Mock
    private ConfigurableEnvironment environment;

    private final ServiceLockInfoHandle lockInfoHandle = new ServiceLockInfoHandle();

    @BeforeEach
    void setUp() {
        when(applicationContext.getEnvironment()).thenReturn(environment);
        when(environment.getProperty(PREFIX_DISTINCTION_NAME, DEFAULT_PREFIX_DISTINCTION_NAME))
                .thenReturn("test-prefix");
        ReflectionTestUtils.setField(SpringUtil.class, "configurableApplicationContext", applicationContext);
    }

    @AfterEach
    void tearDown() {
        ReflectionTestUtils.setField(SpringUtil.class, "configurableApplicationContext", null);
    }

    @Test
    void simpleGetLockNameShouldUseSamePrefixAsAnnotationEntry() {
        String lockName = lockInfoHandle.simpleGetLockName("UPDATE_ORDER_STATUS_LOCK", new String[]{"1001"});
        assertEquals("test-prefix-" + SERVICE_LOCK_PREFIX + ":UPDATE_ORDER_STATUS_LOCK:1001", lockName);
    }
}
