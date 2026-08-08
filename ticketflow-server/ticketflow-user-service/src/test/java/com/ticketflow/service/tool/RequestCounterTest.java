package com.ticketflow.service.tool;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestCounterTest {

    @Test
    void onRequestShouldAllowRequestsBelowThreshold() {
        RequestCounter counter = new RequestCounter();
        ReflectionTestUtils.setField(counter, "maxRequestsPerSecond", 1000);

        assertFalse(counter.onRequest());
    }

    @Test
    void onRequestShouldRejectWhenThresholdExceeded() {
        RequestCounter counter = new RequestCounter();
        ReflectionTestUtils.setField(counter, "maxRequestsPerSecond", 1);

        assertFalse(counter.onRequest());
        assertTrue(counter.onRequest());
    }

    @Test
    void onRequestShouldResetAfterRejection() {
        RequestCounter counter = new RequestCounter();
        ReflectionTestUtils.setField(counter, "maxRequestsPerSecond", 1);

        assertFalse(counter.onRequest());
        assertTrue(counter.onRequest());
        assertFalse(counter.onRequest());
    }
}
