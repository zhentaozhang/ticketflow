package com.ticketflow.initialize.execute;

import com.ticketflow.initialize.base.InitializeHandler;
import com.ticketflow.initialize.execute.base.AbstractApplicationExecute;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AbstractApplicationExecuteTest {

    @Test
    void executeShouldOnlyRunHandlersOfMatchingTypeInOrder() {
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        List<String> executionLog = new ArrayList<>();
        InitializeHandler order30 = recordingHandler("postConstruct", 30, executionLog);
        InitializeHandler order10 = recordingHandler("postConstruct", 10, executionLog);
        InitializeHandler otherType = recordingHandler("startListener", 1, executionLog);

        Map<String, InitializeHandler> handlers = new LinkedHashMap<>();
        handlers.put("h1", order30);
        handlers.put("h2", order10);
        handlers.put("h3", otherType);
        when(context.getBeansOfType(InitializeHandler.class)).thenReturn(handlers);

        AbstractApplicationExecute execute = new AbstractApplicationExecute(context) {
            @Override
            public String type() {
                return "postConstruct";
            }
        };
        execute.execute();

        assertEquals(List.of("order10", "order30"), executionLog);
    }

    @Test
    void executeShouldSkipAllWhenNoHandlerOfMatchingType() {
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        List<String> executionLog = new ArrayList<>();
        InitializeHandler otherType = recordingHandler("startListener", 1, executionLog);
        Map<String, InitializeHandler> handlers = new LinkedHashMap<>();
        handlers.put("h3", otherType);
        when(context.getBeansOfType(InitializeHandler.class)).thenReturn(handlers);

        AbstractApplicationExecute execute = new AbstractApplicationExecute(context) {
            @Override
            public String type() {
                return "postConstruct";
            }
        };
        execute.execute();

        assertTrue(executionLog.isEmpty());
    }

    private InitializeHandler recordingHandler(String type, int order, List<String> log) {
        return new InitializeHandler() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public Integer executeOrder() {
                return order;
            }

            @Override
            public void executeInit(ConfigurableApplicationContext context) {
                log.add("order" + order);
            }
        };
    }
}
