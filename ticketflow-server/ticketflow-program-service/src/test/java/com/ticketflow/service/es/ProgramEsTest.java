package com.ticketflow.service.es;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.ticketflow.core.SpringUtil;
import com.ticketflow.dto.ProgramRecommendListDto;
import com.ticketflow.dto.ProgramSearchDto;
import com.ticketflow.util.BusinessEsHandle;
import com.ticketflow.vo.ProgramListVo;
import com.github.pagehelper.PageInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ProgramEs 单测：不依赖 ES 实例，mock BusinessEsHandle 捕获生成的 JSON DSL，
 * 校验推荐列表 DSL 的结构（分页起点、筛选条件、随机排序）。
 */
class ProgramEsTest {

    private ProgramEs programEs;
    private BusinessEsHandle businessEsHandle;

    @BeforeEach
    void setUp() {
        // ProgramEs 通过 SpringUtil 取索引前缀；单测注入固定前缀的假上下文
        ConfigurableApplicationContext ctx = mock(ConfigurableApplicationContext.class);
        ConfigurableEnvironment env = mock(ConfigurableEnvironment.class);
        when(ctx.getEnvironment()).thenReturn(env);
        when(env.getProperty(anyString(), anyString())).thenReturn("local");
        ReflectionTestUtils.setField(SpringUtil.class, "configurableApplicationContext", ctx);

        programEs = new ProgramEs();
        businessEsHandle = mock(BusinessEsHandle.class);
        ReflectionTestUtils.setField(programEs, "businessEsHandle", businessEsHandle);
    }

    private String capturedDsl(ProgramRecommendListDto dto) throws Exception {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        programEs.recommendList(dto);
        verify(businessEsHandle).executeQuery(anyString(), anyString(),
                ArgumentMatchers.<ProgramListVo>anyList(), isNull(),
                eq(ProgramListVo.class), captor.capture(), isNull());
        return captor.getValue();
    }

    @Test
    void recommendList_fromStartsAtZero() throws Exception {
        ProgramRecommendListDto dto = new ProgramRecommendListDto();
        dto.setAreaId(1L);

        String dsl = capturedDsl(dto);
        assertNotNull(dsl, "应生成查询 DSL");
        JSONObject json = JSON.parseObject(dsl);
        // 原实现 from=1 会漏掉随机排序后的第一条，应为 0
        assertEquals(0, json.getIntValue("from"));
        assertEquals(10, json.getIntValue("size"));
        assertTrue(dsl.contains("\"term\""), "areaId 条件应生成 term 查询");
        assertTrue(dsl.contains("\"_script\""), "推荐应使用脚本随机排序");
    }

    @Test
    void recommendList_withProgramId_usesMustNot() throws Exception {
        ProgramRecommendListDto dto = new ProgramRecommendListDto();
        dto.setAreaId(1L);
        dto.setProgramId(99L);

        String dsl = capturedDsl(dto);
        assertTrue(dsl.contains("must_not"), "传入 programId 时应生成 must_not 排除自身");
    }

    @Test
    void search_highlightHasNoRefAndEachFieldHasTags() throws Exception {
        ProgramSearchDto dto = new ProgramSearchDto();
        dto.setContent("于文文");
        dto.setTimeType(0);
        dto.setType(0);
        dto.setPageNumber(1);
        dto.setPageSize(5);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        programEs.search(dto);
        verify(businessEsHandle).executeQuery(anyString(), anyString(),
                ArgumentMatchers.<ProgramListVo>anyList(), ArgumentMatchers.<PageInfo<ProgramListVo>>any(),
                eq(ProgramListVo.class), captor.capture(), ArgumentMatchers.<String>anyList());

        String dsl = captor.getValue();
        // fastjson 对复用同一数组实例会输出 {"$ref": ...}，ES 不接受；此断言防止回归
        assertFalse(dsl.contains("$ref"), "highlight 不应出现 fastjson $ref 引用");
        JSONObject fields = JSON.parseObject(dsl).getJSONObject("highlight").getJSONObject("fields");
        assertTrue(fields.getJSONObject("title").getJSONArray("pre_tags").contains("<em>"));
        assertTrue(fields.getJSONObject("actor").getJSONArray("pre_tags").contains("<em>"));
    }

    @Test
    void search_rangeUsesEpochMillis() throws Exception {
        ProgramSearchDto dto = new ProgramSearchDto();
        dto.setTimeType(5);
        dto.setType(0);
        dto.setPageNumber(1);
        dto.setPageSize(5);
        dto.setStartDateTime(new Date(0L));
        dto.setEndDateTime(new Date(4102444800000L));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        programEs.search(dto);
        verify(businessEsHandle).executeQuery(anyString(), anyString(),
                ArgumentMatchers.<ProgramListVo>anyList(), ArgumentMatchers.<PageInfo<ProgramListVo>>any(),
                eq(ProgramListVo.class), captor.capture(), ArgumentMatchers.<String>anyList());

        JSONObject bounds = JSON.parseObject(captor.getValue())
                .getJSONObject("query").getJSONObject("bool")
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
