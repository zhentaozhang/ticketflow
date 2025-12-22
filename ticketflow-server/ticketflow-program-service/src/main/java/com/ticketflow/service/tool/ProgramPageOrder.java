package com.ticketflow.service.tool;


import org.elasticsearch.search.sort.SortOrder;

/**
 * 节目列表排序工具。实现按相关度、推荐、最近开场、最新上架等维度的排序逻辑。
 */
public class ProgramPageOrder {
    /** 排序字段名（如 "showTime"、"issueTime" 等） */
    public String sortParam;
    
    /** 排序方向（ASC / DESC） */
    public SortOrder sortOrder;
}
