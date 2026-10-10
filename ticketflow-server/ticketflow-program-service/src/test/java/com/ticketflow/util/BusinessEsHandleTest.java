package com.ticketflow.util;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.ticketflow.dto.EsDataQueryDto;
import org.apache.http.HttpEntity;
import org.apache.http.StatusLine;
import org.apache.http.entity.ContentType;
import org.apache.http.nio.entity.NStringEntity;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BusinessEsHandle 单测：mock RestClient，捕获发出的请求体，校验生成的 JSON DSL。
 * 重点防止 range 的 Date 被 fastjson 序列化成 "yyyy-MM-dd HH:mm:ss"（ES date 字段不接受会 400）。
 */
class BusinessEsHandleTest {

    @Test
    void queryPage_rangeUsesEpochMillis() throws Exception {
        RestClient restClient = mock(RestClient.class);
        Response response = mock(Response.class);
        StatusLine statusLine = mock(StatusLine.class);
        when(statusLine.getStatusCode()).thenReturn(200);
        when(response.getStatusLine()).thenReturn(statusLine);
        when(response.getEntity()).thenReturn(
                new NStringEntity("{\"hits\":{\"total\":{\"value\":0},\"hits\":[]}}", ContentType.APPLICATION_JSON));
        when(restClient.performRequest(any(Request.class))).thenReturn(response);

        BusinessEsHandle handle = new BusinessEsHandle(restClient, true, false);

        EsDataQueryDto dto = new EsDataQueryDto();
        dto.setParamName("showDayTime");
        dto.setStartTime(new Date(0L));
        dto.setEndTime(new Date(4102444800000L));

        handle.queryPage("idx", "type", List.of(dto), 1, 5, Object.class);

        ArgumentCaptor<Request> captor = ArgumentCaptor.forClass(Request.class);
        verify(restClient).performRequest(captor.capture());
        HttpEntity entity = captor.getValue().getEntity();
        JSONObject dsl = JSON.parseObject(EntityUtils.toString(entity));

        JSONObject bounds = dsl.getJSONObject("query").getJSONObject("bool")
                .getJSONArray("must").getJSONObject(0)
                .getJSONObject("range").getJSONObject("showDayTime");
        Object gte = bounds.get("gte");
        Object lte = bounds.get("lte");
        assertTrue(gte instanceof Number, "range gte 应为数字(epoch 毫秒)，不能是日期字符串");
        assertTrue(lte instanceof Number, "range lte 应为数字(epoch 毫秒)，不能是日期字符串");
        assertEquals(0L, ((Number) gte).longValue());
        assertEquals(4102444800000L, ((Number) lte).longValue());
    }
}
