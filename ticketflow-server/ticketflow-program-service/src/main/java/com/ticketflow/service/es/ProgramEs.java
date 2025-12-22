package com.ticketflow.service.es;

import cn.hutool.core.collection.CollectionUtil;
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
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.MatchAllQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.script.Script;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightBuilder;
import org.elasticsearch.search.sort.FieldSortBuilder;
import org.elasticsearch.search.sort.ScriptSortBuilder;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * ES 节目搜索实现。
 * 所有查询都走 ES 索引 "ticketflow-program"，
 * 查询失败（ES 异常或 ES 无数据）时由 ProgramService 兜底走 DB。
 * <p>
 * ES 查询核心概念对应：
 * termQuery     = WHERE field = ?        （精确匹配，适用于 long/数值/keyword 类型）
 * matchQuery    = WHERE field 分词匹配    （全文搜索，适用于 text 类型）
 * boolQuery     = AND(must) / OR(should)  （条件组合）
 * rangeQuery    = WHERE field BETWEEN ?   （时间范围过滤）
 * from/size     = LIMIT ?, ?              （分页）
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
     * 使用 ES 的 ScriptSort + Math.random() 实现随机排序，
     * 避免每次都返回相同数据。
     * <p>
     * 支持排除指定节目 ID（mustNot），用于"看过这个的人也看"场景。
     */
    public List<ProgramListVo> recommendList(ProgramRecommendListDto programRecommendListDto) {
        List<ProgramListVo> programListVoList = new ArrayList<>();
        try {
            // 构建布尔查询：无筛选条件时使用 matchAll，否则按 areaId/parentCategoryId 过滤，按 programId 排除
            boolean allQueryFlag = true;
            MatchAllQueryBuilder matchAllQueryBuilder = QueryBuilders.matchAllQuery();
            BoolQueryBuilder boolQuery = QueryBuilders.boolQuery();
            if (Objects.nonNull(programRecommendListDto.getAreaId())) {
                allQueryFlag = false;
                QueryBuilder builds = QueryBuilders.termQuery(ProgramDocumentParamName.AREA_ID, programRecommendListDto.getAreaId());
                boolQuery.must(builds);
            }
            if (Objects.nonNull(programRecommendListDto.getParentProgramCategoryId())) {
                allQueryFlag = false;
                QueryBuilder builds = QueryBuilders.termQuery(ProgramDocumentParamName.PARENT_PROGRAM_CATEGORY_ID, programRecommendListDto.getParentProgramCategoryId());
                boolQuery.must(builds);
            }
            if (Objects.nonNull(programRecommendListDto.getProgramId())) {
                allQueryFlag = false;
                // mustNot 排除指定节目 ID，实现"看过这个的人也看"排除已看节目
                QueryBuilder builds = QueryBuilders.termQuery(ProgramDocumentParamName.ID, programRecommendListDto.getProgramId());
                boolQuery.mustNot(builds);
            }
            SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
            searchSourceBuilder.query(allQueryFlag ? matchAllQueryBuilder : boolQuery);
            searchSourceBuilder.trackTotalHits(true);
            searchSourceBuilder.from(1);
            searchSourceBuilder.size(10);

            // 使用 ScriptSort + Math.random() 实现随机排序，避免每次返回相同推荐结果
            Script script = new Script("Math.random()");
            ScriptSortBuilder scriptSortBuilder = new ScriptSortBuilder(script, ScriptSortBuilder.ScriptSortType.NUMBER);
            scriptSortBuilder.order(SortOrder.ASC);

            searchSourceBuilder.sort(scriptSortBuilder);

            businessEsHandle.executeQuery(SpringUtil.getPrefixDistinctionName() + "-" + ProgramDocumentParamName.INDEX_NAME, ProgramDocumentParamName.INDEX_TYPE, programListVoList, null, ProgramListVo.class, searchSourceBuilder, null);
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
                programPageOrder.sortOrder = SortOrder.DESC;
                break;
            case 3:
                programPageOrder.sortParam = ProgramDocumentParamName.SHOW_TIME;
                programPageOrder.sortOrder = SortOrder.ASC;
                break;
            case 4:
                programPageOrder.sortParam = ProgramDocumentParamName.ISSUE_TIME;
                programPageOrder.sortOrder = SortOrder.ASC;
                break;
            default:
                programPageOrder.sortParam = null;
                programPageOrder.sortOrder = null;
        }
        return programPageOrder;
    }

    /**
     * 节目搜索方法（全文搜索）。
     * 使用 matchQuery 对 title 和 actor 字段做分词匹配，
     * 命中的关键词用 <em> 标签高亮返回给前端渲染。
     * 支持区域、分类、时间范围、关键词等多条件组合筛选。
     * <p>
     * 搜索条件组合（AND）：
     * areaId=?
     * parentProgramCategoryId=?
     * showDayTime BETWEEN ? AND ?
     * (title 分词匹配 "内容" OR actor 分词匹配 "内容")
     * <p>
     * 注意：matchQuery 走分词，ES mapping 中这两个字段类型为 text。
     * areaId 等精确字段走 termQuery，类型为 long。
     *
     * @param programSearchDto 节目搜索请求参数，包含搜索条件和分页信息
     * @return 分页的节目列表结果
     */
    public PageVo<ProgramListVo> search(ProgramSearchDto programSearchDto) {
        // 初始化返回的分页结果对象
        PageVo<ProgramListVo> pageVo = new PageVo<>();
        try {
            // 1. 构建 ES 布尔查询，用于组合多个搜索条件
            BoolQueryBuilder boolQuery = QueryBuilders.boolQuery();

            // 2. 区域筛选：如果传入了区域ID，则添加精确匹配条件（must 表示必须满足）
            if (Objects.nonNull(programSearchDto.getAreaId())) {
                QueryBuilder builds = QueryBuilders.termQuery(ProgramDocumentParamName.AREA_ID, programSearchDto.getAreaId());
                boolQuery.must(builds);
            }

            // 3. 节目分类筛选：如果传入了父级分类ID，则添加精确匹配条件
            if (Objects.nonNull(programSearchDto.getParentProgramCategoryId())) {
                QueryBuilder builds = QueryBuilders.termQuery(ProgramDocumentParamName.PARENT_PROGRAM_CATEGORY_ID, programSearchDto.getParentProgramCategoryId());
                boolQuery.must(builds);
            }

            // 4. 时间范围筛选：如果起止时间都不为空，则添加时间区间查询
            // includeLower(true) 表示包含下边界（即大于等于开始时间）
            if (Objects.nonNull(programSearchDto.getStartDateTime()) && Objects.nonNull(programSearchDto.getEndDateTime())) {
                QueryBuilder builds = QueryBuilders.rangeQuery(ProgramDocumentParamName.SHOW_DAY_TIME).from(programSearchDto.getStartDateTime()).to(programSearchDto.getEndDateTime()).includeLower(true);
                boolQuery.must(builds);
            }

            // 5. 关键词搜索：如果搜索内容不为空，则在标题和演员字段中进行模糊匹配
            if (StringUtil.isNotEmpty(programSearchDto.getContent())) {
                // 创建内层布尔查询，用于处理多字段的 OR 逻辑
                BoolQueryBuilder innerBoolQuery = QueryBuilders.boolQuery();
                // 在标题中匹配关键词
                innerBoolQuery.should(QueryBuilders.matchQuery(ProgramDocumentParamName.TITLE, programSearchDto.getContent()));
                // 在演员中匹配关键词
                innerBoolQuery.should(QueryBuilders.matchQuery(ProgramDocumentParamName.ACTOR, programSearchDto.getContent()));
                // 设置至少匹配一个条件（标题或演员任一匹配即可）
                innerBoolQuery.minimumShouldMatch(1);
                // 将关键词搜索条件作为外层查询的必须满足条件
                boolQuery.must(innerBoolQuery);
            }

            // 6. 构建搜索请求配置
            SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();

            // 7. 处理排序逻辑：如果指定了排序字段和排序方式，则添加排序规则
            ProgramPageOrder programPageOrder = getProgramPageOrder(programSearchDto);
            if (Objects.nonNull(programPageOrder.sortParam) && Objects.nonNull(programPageOrder.sortOrder)) {
                FieldSortBuilder sort = SortBuilders.fieldSort(programPageOrder.sortParam);
                sort.order(programPageOrder.sortOrder);
                searchSourceBuilder.sort(sort);
            }

            // 8. 设置查询条件
            searchSourceBuilder.query(boolQuery);

            // 9. 启用总数追踪，用于计算总页数（ES 7.0+ 默认最多返回10000条）
            searchSourceBuilder.trackTotalHits(true);

            // 10. 设置分页偏移量：计算从第几条记录开始
            // 例如：第1页，每页10条，则从第0条开始
            searchSourceBuilder.from((programSearchDto.getPageNumber() - 1) * programSearchDto.getPageSize());

            // 11. 设置每页返回的记录数
            searchSourceBuilder.size(programSearchDto.getPageSize());

            // 12. 设置高亮显示：对标题和演员字段进行高亮处理，方便前端展示匹配的关键词
            searchSourceBuilder.highlighter(getHighlightBuilder(Arrays.asList(ProgramDocumentParamName.TITLE, ProgramDocumentParamName.ACTOR)));

            // 13. 准备接收查询结果的列表和分页信息
            List<ProgramListVo> list = new ArrayList<>();
            PageInfo<ProgramListVo> pageInfo = new PageInfo<>(list);
            pageInfo.setPageNum(programSearchDto.getPageNumber());
            pageInfo.setPageSize(programSearchDto.getPageSize());

            // 14. 执行 ES 查询
            // 参数说明：索引名称（带环境前缀）、索引类型、结果列表、分页信息、结果映射类、搜索配置、高亮字段列表
            businessEsHandle.executeQuery(SpringUtil.getPrefixDistinctionName() + "-" + ProgramDocumentParamName.INDEX_NAME, ProgramDocumentParamName.INDEX_TYPE, list, pageInfo, ProgramListVo.class, searchSourceBuilder, Arrays.asList(ProgramDocumentParamName.TITLE, ProgramDocumentParamName.ACTOR));

            // 15. 将 ES 查询结果的分页信息转换为统一的分页 VO 对象
            pageVo = PageUtil.convertPage(pageInfo, programListVo -> programListVo);

        } catch (Exception e) {
            // 16. 异常处理：记录错误日志，返回空的分页结果
            log.error("search error", e);
        }
        return pageVo;
    }

    /**
     * 高亮配置。
     * 命中关键词用 <em>标签</em> 包裹，前端渲染标红。
     */
    public HighlightBuilder getHighlightBuilder(List<String> fieldNameList) {
        HighlightBuilder highlightBuilder = new HighlightBuilder();
        for (String fieldName : fieldNameList) {
            HighlightBuilder.Field highlightTitle = new HighlightBuilder.Field(fieldName);
            highlightTitle.preTags("<em>");
            highlightTitle.postTags("</em>");
            highlightBuilder.field(highlightTitle);
        }
        return highlightBuilder;
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
}
