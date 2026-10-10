package com.ticketflow.service.es;

import cn.hutool.core.collection.CollectionUtil;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.ticketflow.core.SpringUtil;
import com.ticketflow.dto.EsDataQueryDto;
import com.ticketflow.dto.ProgramListDto;
import com.ticketflow.dto.ProgramPageListDto;
import com.ticketflow.dto.ProgramRecommendListDto;
import com.ticketflow.dto.ProgramSearchDto;
import com.ticketflow.enums.BusinessStatus;
import com.ticketflow.page.PageUtil;
import com.ticketflow.page.PageVo;
import com.ticketflow.service.init.ProgramDocumentParamName;
import com.ticketflow.service.tool.ProgramPageOrder;
import com.ticketflow.util.BusinessEsHandle;
import com.ticketflow.util.StringUtil;
import com.ticketflow.vo.ProgramHomeVo;
import com.ticketflow.vo.ProgramListVo;
import com.github.pagehelper.PageInfo;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * ES 节目搜索实现。
 * 所有查询都走 ES 索引 "ticketflow-program"，
 * 查询失败（ES 异常或 ES 无数据）时由 ProgramService 兜底走 DB。
 * <p>
 * 查询 DSL 说明（ES 8 + 低层 RestClient，JSON 由 fastjson 构造）：
 * term       = WHERE field = ?        （精确匹配，适用于 long/数值/keyword 类型）
 * match      = WHERE field 分词匹配    （全文搜索，适用于 text 类型）
 * bool       = AND(must) / OR(should) / NOT(must_not)
 * range      = WHERE field BETWEEN ?   （时间范围过滤）
 * from/size  = LIMIT ?, ?
 */
@Slf4j
@Component
public class ProgramEs {

    @Resource
    private BusinessEsHandle businessEsHandle;

    /**
     * 主页推荐列表。
     * 循环每个父分类，从 ES 查出该分类下前 7 条节目。
     * 有 areaId 则按地区过滤，无则取 prime=1（全国推荐）。
     * <p>
     * 相当于逐条执行:
     * SELECT * FROM ticketflow-program
     * WHERE areaId=? AND parentProgramCategoryId=?
     * LIMIT 7
     * <p>
     * 循环 4 次（每个父分类一次），结果按分类分组返回。
     */
    public List<ProgramHomeVo> selectHomeList(ProgramListDto programListDto) {
        List<ProgramHomeVo> programHomeVoList = new ArrayList<>();

        try {
            // 遍历每个父分类，分别从 ES 查询前 7 条节目
            for (Long parentProgramCategoryId : programListDto.getParentProgramCategoryIds()) {
                List<EsDataQueryDto> programEsQueryDto = new ArrayList<>();
                // 有 areaId 则按地区过滤；无 areaId 则取 prime=1（全国推荐）作为默认区域
                if (Objects.nonNull(programListDto.getAreaId())) {
                    EsDataQueryDto areaIdQueryDto = new EsDataQueryDto();
                    areaIdQueryDto.setParamName(ProgramDocumentParamName.AREA_ID);
                    areaIdQueryDto.setParamValue(programListDto.getAreaId());
                    programEsQueryDto.add(areaIdQueryDto);
                } else {
                    EsDataQueryDto primeQueryDto = new EsDataQueryDto();
                    primeQueryDto.setParamName(ProgramDocumentParamName.PRIME);
                    primeQueryDto.setParamValue(BusinessStatus.YES.getCode());
                    programEsQueryDto.add(primeQueryDto);
                }

                EsDataQueryDto parentProgramCategoryIdQueryDto = new EsDataQueryDto();
                parentProgramCategoryIdQueryDto.setParamName(ProgramDocumentParamName.PARENT_PROGRAM_CATEGORY_ID);
                parentProgramCategoryIdQueryDto.setParamValue(parentProgramCategoryId);
                programEsQueryDto.add(parentProgramCategoryIdQueryDto);

                // 每个分类独立查询 1 页 7 条，结果按分类分组组装
                PageInfo<ProgramListVo> pageInfo = businessEsHandle.queryPage(SpringUtil.getPrefixDistinctionName() + "-" + ProgramDocumentParamName.INDEX_NAME, ProgramDocumentParamName.INDEX_TYPE, programEsQueryDto, 1, 7, ProgramListVo.class);
                if (!pageInfo.getList().isEmpty()) {
                    ProgramHomeVo programHomeVo = new ProgramHomeVo();
                    programHomeVo.setCategoryName(pageInfo.getList().get(0).getParentProgramCategoryName());
                    programHomeVo.setCategoryId(pageInfo.getList().get(0).getParentProgramCategoryId());
                    programHomeVo.setProgramListVoList(pageInfo.getList());
                    programHomeVoList.add(programHomeVo);
                }
            }
        } catch (Exception e) {
            log.error("businessEsHandle.queryPage error", e);
        }
        return programHomeVoList;
    }

    /**
     * 推荐列表（随机展示 10 条）。
     * 使用 ES 的 script sort + Math.random() 实现随机排序，
     * 避免每次都返回相同数据。
     * <p>
     * 支持排除指定节目 ID（must_not），用于"看过这个的人也看"场景。
     */
    public List<ProgramListVo> recommendList(ProgramRecommendListDto programRecommendListDto) {
        List<ProgramListVo> programListVoList = new ArrayList<>();
        try {
            // 构建布尔查询：无筛选条件时使用 match_all，否则按 areaId/parentCategoryId 过滤，按 programId 排除
            boolean allQueryFlag = true;
            JSONArray must = new JSONArray();
            JSONArray mustNot = new JSONArray();
            if (Objects.nonNull(programRecommendListDto.getAreaId())) {
                allQueryFlag = false;
                must.add(termQuery(ProgramDocumentParamName.AREA_ID, programRecommendListDto.getAreaId()));
            }
            if (Objects.nonNull(programRecommendListDto.getParentProgramCategoryId())) {
                allQueryFlag = false;
                must.add(termQuery(ProgramDocumentParamName.PARENT_PROGRAM_CATEGORY_ID, programRecommendListDto.getParentProgramCategoryId()));
            }
            if (Objects.nonNull(programRecommendListDto.getProgramId())) {
                allQueryFlag = false;
                // must_not 排除指定节目 ID，实现"看过这个的人也看"排除已看节目
                mustNot.add(termQuery(ProgramDocumentParamName.ID, programRecommendListDto.getProgramId()));
            }

            JSONObject dsl = new JSONObject();
            if (allQueryFlag) {
                dsl.put("query", matchAllQuery());
            } else {
                dsl.put("query", boolQuery(must, null, mustNot));
            }
            dsl.put("track_total_hits", true);
            dsl.put("from", 0);
            dsl.put("size", 10);

            // 使用 script sort + Math.random() 实现随机排序，避免每次返回相同推荐结果
            JSONObject script = new JSONObject();
            script.put("source", "Math.random()");
            JSONObject scriptSort = new JSONObject();
            scriptSort.put("type", "number");
            scriptSort.put("script", script);
            scriptSort.put("order", "asc");
            JSONArray sort = new JSONArray();
            JSONObject scriptWrap = new JSONObject();
            scriptWrap.put("_script", scriptSort);
            sort.add(scriptWrap);
            dsl.put("sort", sort);

            businessEsHandle.executeQuery(SpringUtil.getPrefixDistinctionName() + "-" + ProgramDocumentParamName.INDEX_NAME, ProgramDocumentParamName.INDEX_TYPE, programListVoList, null, ProgramListVo.class, dsl.toJSONString(), null);
        } catch (Exception e) {
            log.error("recommendList error", e);
        }
        return programListVoList;
    }


    /**
     * 分页列表查询。
     * 支持按地区、父分类、子分类过滤，按时间范围筛选。
     * 排序策略由 getProgramPageOrder() 决定（热度/开场时间/上架时间）。
     * <p>
     * 所有条件通过 EsDataQueryDto 列表传入 BusinessEsHandle，
     * BusinessEsHandle 内部自动拼接为 termQuery + rangeQuery 的 AND 组合。
     */
    public PageVo<ProgramListVo> selectPage(ProgramPageListDto programPageListDto) {
        PageVo<ProgramListVo> pageVo = new PageVo<>();
        try {
            List<EsDataQueryDto> esDataQueryDtoList = new ArrayList<>();

            if (Objects.nonNull(programPageListDto.getAreaId())) {
                //地区id条件
                EsDataQueryDto areaIdQueryDto = new EsDataQueryDto();
                areaIdQueryDto.setParamName(ProgramDocumentParamName.AREA_ID);
                areaIdQueryDto.setParamValue(programPageListDto.getAreaId());
                esDataQueryDtoList.add(areaIdQueryDto);
            } else {
                //如果查全部地区，那么需要指定同一个节目分组内的主要节目
                EsDataQueryDto primeQueryDto = new EsDataQueryDto();
                primeQueryDto.setParamName(ProgramDocumentParamName.PRIME);
                primeQueryDto.setParamValue(BusinessStatus.YES.getCode());
                esDataQueryDtoList.add(primeQueryDto);
            }
            //父节目类型条件
            if (Objects.nonNull(programPageListDto.getParentProgramCategoryId())) {
                EsDataQueryDto parentProgramCategoryIdQueryDto = new EsDataQueryDto();
                parentProgramCategoryIdQueryDto.setParamName(ProgramDocumentParamName.PARENT_PROGRAM_CATEGORY_ID);
                parentProgramCategoryIdQueryDto.setParamValue(programPageListDto.getParentProgramCategoryId());
                esDataQueryDtoList.add(parentProgramCategoryIdQueryDto);
            }
            //节目类型条件
            if (Objects.nonNull(programPageListDto.getProgramCategoryId())) {
                EsDataQueryDto programCategoryIdQueryDto = new EsDataQueryDto();
                programCategoryIdQueryDto.setParamName(ProgramDocumentParamName.PROGRAM_CATEGORY_ID);
                programCategoryIdQueryDto.setParamValue(programPageListDto.getProgramCategoryId());
                esDataQueryDtoList.add(programCategoryIdQueryDto);
            }
            //开始日期和结束日期条件
            if (Objects.nonNull(programPageListDto.getStartDateTime()) &&
                    Objects.nonNull(programPageListDto.getEndDateTime())) {
                EsDataQueryDto showDayTimeQueryDto = new EsDataQueryDto();
                showDayTimeQueryDto.setParamName(ProgramDocumentParamName.SHOW_DAY_TIME);
                showDayTimeQueryDto.setStartTime(programPageListDto.getStartDateTime());
                showDayTimeQueryDto.setEndTime(programPageListDto.getEndDateTime());
                esDataQueryDtoList.add(showDayTimeQueryDto);
            }
            //构建排序信息
            ProgramPageOrder programPageOrder = getProgramPageOrder(programPageListDto);

            PageInfo<ProgramListVo> programListVoPageInfo = businessEsHandle.queryPage(
                    SpringUtil.getPrefixDistinctionName() + "-" + ProgramDocumentParamName.INDEX_NAME,
                    ProgramDocumentParamName.INDEX_TYPE, esDataQueryDtoList, programPageOrder.sortParam,
                    programPageOrder.sortOrder, programPageListDto.getPageNumber(), programPageListDto.getPageSize(),
                    ProgramListVo.class);
            pageVo = PageUtil.convertPage(programListVoPageInfo, programListVo -> programListVo);
        } catch (Exception e) {
            log.error("selectPage error", e);
        }
        return pageVo;
    }

    /**
     * 根据前端传入的 type 返回排序字段和方向。
     * type=2: 按热度倒序
     * type=3: 按开场时间正序（即将开始的在前）
     * type=4: 按上架时间正序
     */
    public ProgramPageOrder getProgramPageOrder(ProgramPageListDto programPageListDto) {
        ProgramPageOrder programPageOrder = new ProgramPageOrder();
        switch (programPageListDto.getType()) {
            case 2:
                programPageOrder.sortParam = ProgramDocumentParamName.HIGH_HEAT;
                programPageOrder.sortOrder = "desc";
                break;
            case 3:
                programPageOrder.sortParam = ProgramDocumentParamName.SHOW_TIME;
                programPageOrder.sortOrder = "asc";
                break;
            case 4:
                programPageOrder.sortParam = ProgramDocumentParamName.ISSUE_TIME;
                programPageOrder.sortOrder = "asc";
                break;
            default:
                programPageOrder.sortParam = null;
                programPageOrder.sortOrder = null;
        }
        return programPageOrder;
    }

    /**
     * 节目搜索方法（全文搜索）。
     * 使用 match 查询对 title 和 actor 字段做分词匹配，
     * 命中的关键词用 &lt;em&gt; 标签高亮返回给前端渲染。
     * 支持区域、分类、时间范围、关键词等多条件组合筛选。
     * <p>
     * 搜索条件组合（AND）：
     * areaId=?
     * parentProgramCategoryId=?
     * showDayTime BETWEEN ? AND ?
     * (title 分词匹配 "内容" OR actor 分词匹配 "内容")
     *
     * @param programSearchDto 节目搜索请求参数，包含搜索条件和分页信息
     * @return 分页的节目列表结果
     */
    public PageVo<ProgramListVo> search(ProgramSearchDto programSearchDto) {
        // 初始化返回的分页结果对象
        PageVo<ProgramListVo> pageVo = new PageVo<>();
        try {
            // 1. 构建 ES bool 查询的 must 条件集合（AND）
            JSONArray must = new JSONArray();

            // 2. 区域筛选：精确匹配（term）
            if (Objects.nonNull(programSearchDto.getAreaId())) {
                must.add(termQuery(ProgramDocumentParamName.AREA_ID, programSearchDto.getAreaId()));
            }

            // 3. 节目分类筛选：精确匹配（term）
            if (Objects.nonNull(programSearchDto.getParentProgramCategoryId())) {
                must.add(termQuery(ProgramDocumentParamName.PARENT_PROGRAM_CATEGORY_ID, programSearchDto.getParentProgramCategoryId()));
            }

            // 4. 时间范围筛选：range，gte 包含下边界
            if (Objects.nonNull(programSearchDto.getStartDateTime()) && Objects.nonNull(programSearchDto.getEndDateTime())) {
                must.add(rangeQuery(ProgramDocumentParamName.SHOW_DAY_TIME, programSearchDto.getStartDateTime(), programSearchDto.getEndDateTime()));
            }

            // 5. 关键词搜索：title/actor 分词匹配（OR，至少命中一个）
            if (StringUtil.isNotEmpty(programSearchDto.getContent())) {
                JSONArray should = new JSONArray();
                should.add(matchQuery(ProgramDocumentParamName.TITLE, programSearchDto.getContent()));
                should.add(matchQuery(ProgramDocumentParamName.ACTOR, programSearchDto.getContent()));
                JSONObject innerBool = new JSONObject();
                innerBool.put("should", should);
                innerBool.put("minimum_should_match", 1);
                JSONObject innerQuery = new JSONObject();
                innerQuery.put("bool", innerBool);
                must.add(innerQuery);
            }

            // 6. 构建搜索 DSL
            JSONObject dsl = new JSONObject();
            dsl.put("query", boolQuery(must, null, null));

            // 7. 排序（可选）
            ProgramPageOrder programPageOrder = getProgramPageOrder(programSearchDto);
            if (Objects.nonNull(programPageOrder.sortParam) && Objects.nonNull(programPageOrder.sortOrder)) {
                JSONObject orderBody = new JSONObject();
                orderBody.put("order", programPageOrder.sortOrder);
                JSONObject fieldSort = new JSONObject();
                fieldSort.put(programPageOrder.sortParam, orderBody);
                JSONArray sort = new JSONArray();
                sort.add(fieldSort);
                dsl.put("sort", sort);
            }

            // 8. 启用总数追踪，用于计算总页数
            dsl.put("track_total_hits", true);

            // 9. 分页（from/size）
            dsl.put("from", (programSearchDto.getPageNumber() - 1) * programSearchDto.getPageSize());
            dsl.put("size", programSearchDto.getPageSize());

            // 10. 高亮：title/actor 命中关键词用 <em> 包裹
            dsl.put("highlight", highlightBuilder(Arrays.asList(ProgramDocumentParamName.TITLE, ProgramDocumentParamName.ACTOR)));

            // 11. 准备接收查询结果的列表和分页信息
            List<ProgramListVo> list = new ArrayList<>();
            PageInfo<ProgramListVo> pageInfo = new PageInfo<>(list);
            pageInfo.setPageNum(programSearchDto.getPageNumber());
            pageInfo.setPageSize(programSearchDto.getPageSize());

            // 12. 执行 ES 查询
            businessEsHandle.executeQuery(SpringUtil.getPrefixDistinctionName() + "-" + ProgramDocumentParamName.INDEX_NAME, ProgramDocumentParamName.INDEX_TYPE, list, pageInfo, ProgramListVo.class, dsl.toJSONString(), Arrays.asList(ProgramDocumentParamName.TITLE, ProgramDocumentParamName.ACTOR));

            // 13. 转换为统一分页 VO
            pageVo = PageUtil.convertPage(pageInfo, programListVo -> programListVo);

        } catch (Exception e) {
            // 14. 异常处理：记录错误日志，返回空的分页结果
            log.error("search error", e);
        }
        return pageVo;
    }

    /**
     * 高亮配置：命中关键词用 &lt;em&gt; 标签包裹。
     */
    public JSONObject highlightBuilder(List<String> fieldNameList) {
        JSONObject fields = new JSONObject();
        for (String fieldName : fieldNameList) {
            // 每个字段用独立的数组实例：fastjson 对同一对象多次引用会序列化成 {"$ref": ...}，
            // 而 ES 的 highlight.pre_tags 不接受引用语法，会导致查询报错。
            JSONArray preTags = new JSONArray();
            preTags.add("<em>");
            JSONArray postTags = new JSONArray();
            postTags.add("</em>");
            JSONObject field = new JSONObject();
            field.put("pre_tags", preTags);
            field.put("post_tags", postTags);
            fields.put(fieldName, field);
        }
        JSONObject highlight = new JSONObject();
        highlight.put("fields", fields);
        return highlight;
    }

    /**
     * 根据节目 ID 删除 ES 文档。
     * 先查到文档的 ES 内部 _id，再逐条删除。
     * 节目下架时调用。
     */
    public void deleteByProgramId(Long programId) {
        try {
            List<EsDataQueryDto> esDataQueryDtoList = new ArrayList<>();
            EsDataQueryDto programIdDto = new EsDataQueryDto();
            programIdDto.setParamName(ProgramDocumentParamName.ID);
            programIdDto.setParamValue(programId);
            esDataQueryDtoList.add(programIdDto);

            List<ProgramListVo> programListVos = businessEsHandle.query(SpringUtil.getPrefixDistinctionName() + "-" + ProgramDocumentParamName.INDEX_NAME, ProgramDocumentParamName.INDEX_TYPE, esDataQueryDtoList, ProgramListVo.class);
            if (CollectionUtil.isNotEmpty(programListVos)) {
                for (ProgramListVo programListVo : programListVos) {
                    businessEsHandle.deleteByDocumentId(SpringUtil.getPrefixDistinctionName() + "-" + ProgramDocumentParamName.INDEX_NAME, programListVo.getEsId());
                }
            }
        } catch (Exception e) {
            log.error("deleteByProgramId error", e);
        }
    }

    private JSONObject termQuery(String field, Object value) {
        JSONObject body = new JSONObject();
        body.put(field, value);
        JSONObject query = new JSONObject();
        query.put("term", body);
        return query;
    }

    private JSONObject matchQuery(String field, Object value) {
        JSONObject body = new JSONObject();
        body.put(field, value);
        JSONObject query = new JSONObject();
        query.put("match", body);
        return query;
    }

    private JSONObject rangeQuery(String field, Date startTime, Date endTime) {
        JSONObject bounds = new JSONObject();
        // 用 epoch 毫秒，而不是 Date 对象：fastjson 会把 Date 序列化成 "yyyy-MM-dd HH:mm:ss"，
        // ES 的 date 字段不接受该格式（会 400）。
        if (Objects.nonNull(startTime)) {
            bounds.put("gte", startTime.getTime());
        }
        if (Objects.nonNull(endTime)) {
            bounds.put("lte", endTime.getTime());
        }
        JSONObject body = new JSONObject();
        body.put(field, bounds);
        JSONObject query = new JSONObject();
        query.put("range", body);
        return query;
    }

    private JSONObject matchAllQuery() {
        JSONObject query = new JSONObject();
        query.put("match_all", new JSONObject());
        return query;
    }

    private JSONObject boolQuery(JSONArray must, JSONArray should, JSONArray mustNot) {
        JSONObject bool = new JSONObject();
        if (Objects.nonNull(must) && !must.isEmpty()) {
            bool.put("must", must);
        }
        if (Objects.nonNull(should) && !should.isEmpty()) {
            bool.put("should", should);
        }
        if (Objects.nonNull(mustNot) && !mustNot.isEmpty()) {
            bool.put("must_not", mustNot);
        }
        JSONObject query = new JSONObject();
        query.put("bool", bool);
        return query;
    }
}
