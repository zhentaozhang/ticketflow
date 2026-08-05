package com.ticketflow.service.init;

import com.ticketflow.BusinessThreadPool;
import com.ticketflow.core.SpringUtil;
import com.ticketflow.dto.EsDocumentMappingDto;
import com.ticketflow.entity.TicketCategoryAggregate;
import com.ticketflow.initialize.base.AbstractApplicationPostConstructHandler;
import com.ticketflow.service.ProgramService;
import com.ticketflow.util.BusinessEsHandle;
import com.ticketflow.vo.ProgramVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 节目ES索引初始化。启动时将数据库中的节目数据全量同步到Elasticsearch索引。
 */
@Slf4j
@Component
public class ProgramElasticsearchInitData extends AbstractApplicationPostConstructHandler {

    @Autowired(required = false)
    private BusinessEsHandle businessEsHandle;

    @Autowired
    private ProgramService programService;


    /**
     * 执行优先级
     *
     * @return 3（在 ProgramCategoryInitData、ProgramShowTimeRenewal 之后执行）
     */
    @Override
    public Integer executeOrder() {
        return 3;
    }

    /**
     * 异步执行 ES 索引初始化
     *
     * @param context Spring 应用上下文
     */
    @Override
    public void executeInit(final ConfigurableApplicationContext context) {
        if (businessEsHandle == null) {
            log.warn("ES not configured, skip ES data initialization");
            return;
        }
        BusinessThreadPool.execute(() -> {
            try {
                initElasticsearchData();
            } catch (Exception e) {
                log.error("executeInit error", e);
            }
        });
    }

    /**
     * 全量同步节目数据到 Elasticsearch
     * 1. 删除旧索引 + 创建新索引
     * 2. 从数据库加载所有节目信息
     * 3. 预加载票档价格聚合（min/max）
     * 4. 逐条写入 ES document
     */
    public void initElasticsearchData() {
        // 删除旧索引 + 创建新索引（含 mapping），若失败则跳过全量同步
        if (!indexAdd()) {
            return;
        }
        List<Long> allProgramIdList = programService.getAllProgramIdList();
        // 预加载所有票档价格聚合数据（MIN/MAX price），避免逐条查询
        Map<Long, TicketCategoryAggregate> ticketCategorieMap = programService.selectTicketCategorieMap(allProgramIdList);

        List<Map<String, Object>> programDocList = new ArrayList<>(allProgramIdList.size());
        for (Long programId : allProgramIdList) {
            ProgramVo programVo = programService.getDetailFromDb(programId);
            // 将 ProgramVo 字段映射为 ES document 的 flat map（20+ 字段）
            Map<String, Object> map = new HashMap<>(32);
            map.put(ProgramDocumentParamName.ID, programVo.getId());
            map.put(ProgramDocumentParamName.PROGRAM_GROUP_ID, programVo.getProgramGroupId());
            map.put(ProgramDocumentParamName.PRIME, programVo.getPrime());
            map.put(ProgramDocumentParamName.TITLE, programVo.getTitle());
            map.put(ProgramDocumentParamName.ACTOR, programVo.getActor());
            map.put(ProgramDocumentParamName.PLACE, programVo.getPlace());
            map.put(ProgramDocumentParamName.ITEM_PICTURE, programVo.getItemPicture());
            map.put(ProgramDocumentParamName.AREA_ID, programVo.getAreaId());
            map.put(ProgramDocumentParamName.AREA_NAME, programVo.getAreaName());
            map.put(ProgramDocumentParamName.PROGRAM_CATEGORY_ID, programVo.getProgramCategoryId());
            map.put(ProgramDocumentParamName.PROGRAM_CATEGORY_NAME, programVo.getProgramCategoryName());
            map.put(ProgramDocumentParamName.PARENT_PROGRAM_CATEGORY_ID, programVo.getParentProgramCategoryId());
            map.put(ProgramDocumentParamName.PARENT_PROGRAM_CATEGORY_NAME, programVo.getParentProgramCategoryName());
            map.put(ProgramDocumentParamName.HIGH_HEAT, programVo.getHighHeat());
            map.put(ProgramDocumentParamName.ISSUE_TIME, programVo.getIssueTime());
            map.put(ProgramDocumentParamName.SHOW_TIME, programVo.getShowTime());
            map.put(ProgramDocumentParamName.SHOW_DAY_TIME, programVo.getShowDayTime());
            map.put(ProgramDocumentParamName.SHOW_WEEK_TIME, programVo.getShowWeekTime());
            // MIN_PRICE/MAX_PRICE 来自 TicketCategory 聚合查询（非 ProgramVo 直接字段）
            map.put(ProgramDocumentParamName.MIN_PRICE,
                    Optional.ofNullable(ticketCategorieMap.get(programVo.getId()))
                            .map(TicketCategoryAggregate::getMinPrice).orElse(null));
            map.put(ProgramDocumentParamName.MAX_PRICE,
                    Optional.ofNullable(ticketCategorieMap.get(programVo.getId()))
                            .map(TicketCategoryAggregate::getMaxPrice).orElse(null));
            programDocList.add(map);
        }
        // 批量写入（Bulk API 每 500 条一批）
        businessEsHandle.batchAdd(SpringUtil.getPrefixDistinctionName() + "-" +
                ProgramDocumentParamName.INDEX_NAME, ProgramDocumentParamName.INDEX_TYPE, programDocList);
    }

    /**
     * 重建 ES 索引：删除旧索引（若存在）→ 创建新索引（含 mapping）
     *
     * @return true 索引创建成功，false 创建失败
     */
    public boolean indexAdd() {
        boolean result = businessEsHandle.checkIndex(SpringUtil.getPrefixDistinctionName() + "-" +
                ProgramDocumentParamName.INDEX_NAME, ProgramDocumentParamName.INDEX_TYPE);
        if (result) {
            businessEsHandle.deleteIndex(SpringUtil.getPrefixDistinctionName() + "-" +
                    ProgramDocumentParamName.INDEX_NAME);
        }
        try {
            businessEsHandle.createIndex(SpringUtil.getPrefixDistinctionName() + "-" +
                    ProgramDocumentParamName.INDEX_NAME, ProgramDocumentParamName.INDEX_TYPE, getEsMapping());
            return true;
        } catch (Exception e) {
            log.error("createIndex error", e);
        }
        return false;
    }

    /**
     * 定义节目 ES 文档字段映射
     *
     * @return 字段映射列表（字段名 → ES 数据类型）
     */
    public List<EsDocumentMappingDto> getEsMapping() {
        List<EsDocumentMappingDto> list = new ArrayList<>();

        list.add(new EsDocumentMappingDto(ProgramDocumentParamName.ID, "long"));
        list.add(new EsDocumentMappingDto(ProgramDocumentParamName.PROGRAM_GROUP_ID, "integer"));
        list.add(new EsDocumentMappingDto(ProgramDocumentParamName.PRIME, "long"));
        list.add(new EsDocumentMappingDto(ProgramDocumentParamName.TITLE, "text"));
        list.add(new EsDocumentMappingDto(ProgramDocumentParamName.ACTOR, "text"));
        list.add(new EsDocumentMappingDto(ProgramDocumentParamName.PLACE, "text"));
        list.add(new EsDocumentMappingDto(ProgramDocumentParamName.ITEM_PICTURE, "text"));
        list.add(new EsDocumentMappingDto(ProgramDocumentParamName.AREA_ID, "long"));
        list.add(new EsDocumentMappingDto(ProgramDocumentParamName.AREA_NAME, "text"));
        list.add(new EsDocumentMappingDto(ProgramDocumentParamName.PROGRAM_CATEGORY_ID, "long"));
        list.add(new EsDocumentMappingDto(ProgramDocumentParamName.PROGRAM_CATEGORY_NAME, "text"));
        list.add(new EsDocumentMappingDto(ProgramDocumentParamName.PARENT_PROGRAM_CATEGORY_ID, "long"));
        list.add(new EsDocumentMappingDto(ProgramDocumentParamName.PARENT_PROGRAM_CATEGORY_NAME, "text"));
        list.add(new EsDocumentMappingDto(ProgramDocumentParamName.HIGH_HEAT, "integer"));
        list.add(new EsDocumentMappingDto(ProgramDocumentParamName.ISSUE_TIME, "date"));
        list.add(new EsDocumentMappingDto(ProgramDocumentParamName.SHOW_TIME, "date"));
        list.add(new EsDocumentMappingDto(ProgramDocumentParamName.SHOW_DAY_TIME, "date"));
        list.add(new EsDocumentMappingDto(ProgramDocumentParamName.SHOW_WEEK_TIME, "text"));
        list.add(new EsDocumentMappingDto(ProgramDocumentParamName.MIN_PRICE, "integer"));
        list.add(new EsDocumentMappingDto(ProgramDocumentParamName.MAX_PRICE, "integer"));

        return list;
    }
}
