package com.ticketflow.util;

import cn.hutool.core.collection.CollectionUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.ticketflow.dto.EsDataQueryDto;
import com.ticketflow.dto.EsDocumentMappingDto;
import com.github.pagehelper.PageInfo;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.nio.entity.NStringEntity;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.sort.FieldSortBuilder;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ES 业务操作封装（RestClient 原生 HTTP 方式，非 Spring Data）。
 * 提供：索引创建/删除、文档增删改查、Bool 复合查询、分页搜索、高亮。
 * esSwitch=false 时所有操作静默跳过（调试/降级用）。
 * esTypeSwitch 兼容 ES 6.x（type）与 7.x+（无 type）两种模式。
 */
@Slf4j
@AllArgsConstructor
public class BusinessEsHandle {

    private static final int BULK_BATCH_SIZE = 500;

    private static final String NDJSON_CONTENT_TYPE = "application/x-ndjson";

    private final RestClient restClient;

    private final Boolean esSwitch;

    private final Boolean esTypeSwitch;

    /**
     * 创建索引
     *
     * @param indexName 索引名字
     * @param indexType 索引类型
     * @param list      参数集合
     */
    public void createIndex(String indexName, String indexType, List<EsDocumentMappingDto> list) throws IOException {
        if (!esSwitch) {
            return;
        }
        if (CollectionUtil.isEmpty(list)) {
            return;
        }
        IndexRequest indexRequest = new IndexRequest();
        XContentBuilder builder = JsonXContent.contentBuilder().startObject().startObject("mappings");
        if (esTypeSwitch) {
            builder = builder.startObject(indexType);
        }
        builder = builder.startObject("properties");
        for (EsDocumentMappingDto esDocumentMappingDto : list) {
            String paramName = esDocumentMappingDto.getParamName();
            String paramType = esDocumentMappingDto.getParamType();
            if ("text".equals(paramType)) {
                Map<String, Map<String, Object>> map1 = new HashMap<>(8);
                Map<String, Object> map2 = new HashMap<>(8);
                map2.put("type", "keyword");
                map2.put("ignore_above", 256);
                map1.put("keyword", map2);
                builder = builder.startObject(paramName).field("type", "text").field("fields", map1).endObject();
            } else {
                builder = builder.startObject(paramName).field("type", paramType).endObject();
            }
        }
        if (esTypeSwitch) {
            builder.endObject();
        }
        builder = builder.endObject().endObject().startObject("settings").field("number_of_shards", 1)
                .field("number_of_replicas", 1).endObject().endObject();

        indexRequest.source(builder);
        String source = indexRequest.source().utf8ToString();
        log.info("create index execute dsl : {}", source);
        HttpEntity entity = new NStringEntity(source, ContentType.APPLICATION_JSON);
        execute("PUT", "/" + indexName, entity);
    }

    /**
     * 检查索引是否存在
     *
     * @param indexName 索引名字
     * @param indexType 索引类型
     * @return boolean
     */
    public boolean checkIndex(String indexName, String indexType) {
        if (!esSwitch) {
            return false;
        }
        try {
            String path;
            if (esTypeSwitch) {
                path = "/" + indexName + "/" + indexType + "/_mapping";
            } else {
                path = "/" + indexName + "/_mapping";
            }
            Response response = execute("GET", path, null);
            return response.getStatusLine().getStatusCode() == RestStatus.OK.getStatus();
        } catch (Exception e) {
            if (e instanceof ResponseException && ((ResponseException) e).getResponse().getStatusLine().getStatusCode() == RestStatus.NOT_FOUND.getStatus()) {
                log.warn("index not exist ! indexName:{}, indexType:{}", indexName, indexType);
            } else {
                log.error("checkIndex error", e);
            }
            return false;
        }
    }

    /**
     * 删除索引
     *
     * @param indexName 索引名字
     * @return boolean
     */
    public boolean deleteIndex(String indexName) {
        if (!esSwitch) {
            return false;
        }
        try {
            Response response = execute("DELETE", "/" + indexName, null);
            return response.getStatusLine().getStatusCode() == RestStatus.OK.getStatus();
        } catch (Exception e) {
            log.error("deleteIndex error", e);
        }
        return false;
    }

    /**
     * 添加
     *
     * @param indexName 索引名字
     * @param indexType 索引类型
     * @param params    参数 key:字段名 value:具体值
     * @return boolean
     */
    public boolean add(String indexName, String indexType, Map<String, Object> params) {
        if (!esSwitch) {
            return false;
        }
        if (CollectionUtil.isEmpty(params)) {
            return false;
        }
        String endpoint;
        if (esTypeSwitch) {
            endpoint = "/" + indexName + "/" + indexType;
        } else {
            endpoint = "/" + indexName + "/_doc";
        }
        return doAdd("POST", endpoint, params);
    }

    /**
     * 添加（指定文档 ID，幂等 upsert）
     *
     * @param indexName  索引名字
     * @param indexType  索引类型
     * @param documentId 文档 ID
     * @param params     参数 key:字段名 value:具体值
     * @return boolean
     */
    public boolean add(String indexName, String indexType, String documentId, Map<String, Object> params) {
        if (!esSwitch) {
            return false;
        }
        if (CollectionUtil.isEmpty(params)) {
            return false;
        }
        String endpoint;
        if (esTypeSwitch) {
            endpoint = "/" + indexName + "/" + indexType + "/" + documentId;
        } else {
            endpoint = "/" + indexName + "/_doc/" + documentId;
        }
        return doAdd("PUT", endpoint, params);
    }

    private boolean doAdd(String method, String endpoint, Map<String, Object> params) {
        try {
            String jsonString = JSON.toJSONString(params);
            HttpEntity entity = new NStringEntity(jsonString, ContentType.APPLICATION_JSON);
            log.info("add dsl : {}", jsonString);
            Response indexResponse = execute(method, endpoint, entity);
            int statusCode = indexResponse.getStatusLine().getStatusCode();
            return statusCode == 201 || statusCode == 200;
        } catch (Exception e) {
            log.error("add error", e);
        }
        return false;
    }

    /**
     * 批量添加（Bulk API，每 500 条一批）
     *
     * @param indexName 索引名字
     * @param indexType 索引类型
     * @param params    参数列表 key:字段名 value:具体值
     * @return boolean 全部批次成功返回 true
     */
    public boolean batchAdd(String indexName, String indexType, List<Map<String, Object>> params) {
        if (!esSwitch) {
            return false;
        }
        if (CollectionUtil.isEmpty(params)) {
            return false;
        }
        boolean allSuccess = true;
        for (int i = 0; i < params.size(); i += BULK_BATCH_SIZE) {
            List<Map<String, Object>> batch = params.subList(i, Math.min(i + BULK_BATCH_SIZE, params.size()));
            if (!doBulkAdd(indexName, indexType, batch)) {
                allSuccess = false;
            }
        }
        return allSuccess;
    }

    private boolean doBulkAdd(String indexName, String indexType, List<Map<String, Object>> batch) {
        try {
            StringBuilder bulkBody = new StringBuilder(batch.size() * 128);
            for (Map<String, Object> param : batch) {
                bulkBody.append("{\"index\":{}}\n");
                bulkBody.append(JSON.toJSONString(param)).append('\n');
            }
            String endpoint;
            if (esTypeSwitch) {
                endpoint = "/" + indexName + "/" + indexType + "/_bulk";
            } else {
                endpoint = "/" + indexName + "/_bulk";
            }
            log.info("batchAdd dsl : {}", bulkBody);
            HttpEntity entity = new NStringEntity(bulkBody.toString(), ContentType.create(NDJSON_CONTENT_TYPE, StandardCharsets.UTF_8));
            Response response = execute("POST", endpoint, entity);
            if (response.getStatusLine().getStatusCode() != RestStatus.OK.getStatus()) {
                log.error("batchAdd http error, indexName:{}, statusCode:{}", indexName, response.getStatusLine().getStatusCode());
                return false;
            }
            String result = EntityUtils.toString(response.getEntity());
            JSONObject resultJsonObject = JSONObject.parseObject(result);
            if (Objects.nonNull(resultJsonObject) && Boolean.TRUE.equals(resultJsonObject.getBoolean("errors"))) {
                log.error("batchAdd partial errors, indexName:{}, result:{}", indexName, result);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.error("batchAdd error", e);
        }
        return false;
    }

    /**
     * 查询
     *
     * @param indexName          索引名字
     * @param indexType          索引类型
     * @param esDataQueryDtoList 参数
     * @param clazz              返回的类型
     * @return List
     */
    public <T> List<T> query(String indexName, String indexType, List<EsDataQueryDto> esDataQueryDtoList, Class<T> clazz) throws IOException {
        List<T> list = new ArrayList<>();
        if (!esSwitch) {
            return list;
        }
        SearchSourceBuilder sourceBuilder = getSearchSourceBuilder(esDataQueryDtoList, null, null);
        executeQuery(indexName, indexType, list, null, clazz, sourceBuilder, null);
        return list;
    }

    /**
     * 查询(分页)
     *
     * @param indexName          索引名字
     * @param indexType          索引类型
     * @param esDataQueryDtoList 参数
     * @param pageNo             页码
     * @param pageSize           页大小
     * @param clazz              返回的类型
     * @return PageInfo
     */
    public <T> PageInfo<T> queryPage(String indexName, String indexType, List<EsDataQueryDto> esDataQueryDtoList, Integer pageNo, Integer pageSize, Class<T> clazz) throws IOException {
        return queryPage(indexName, indexType, esDataQueryDtoList, null, null, pageNo, pageSize, clazz);
    }

    /**
     * 查询(分页)
     *
     * @param indexName          索引名字
     * @param indexType          索引类型
     * @param esDataQueryDtoList 参数
     * @param sortParam          排序参数 不排序则为空 如果进行了排序，会返回es中的排序字段sort，需要用户在返回的实体类中添加sort字段
     * @param sortOrder          升序还是降序，为空则降序
     * @param pageNo             页码
     * @param pageSize           页大小
     * @param clazz              返回的类型
     * @return PageInfo
     */
    public <T> PageInfo<T> queryPage(String indexName, String indexType, List<EsDataQueryDto> esDataQueryDtoList, String sortParam, SortOrder sortOrder, Integer pageNo, Integer pageSize, Class<T> clazz) throws IOException {
        List<T> list = new ArrayList<>();
        PageInfo<T> pageInfo = new PageInfo<>(list);
        pageInfo.setPageNum(pageNo);
        pageInfo.setPageSize(pageSize);
        if (!esSwitch) {
            return pageInfo;
        }
        if (Objects.isNull(pageNo) || Objects.isNull(pageSize) || pageNo <= 0 || pageSize <= 0) {
            log.warn("queryPage invalid page params, indexName:{}, pageNo:{}, pageSize:{}", indexName, pageNo, pageSize);
            return pageInfo;
        }
        SearchSourceBuilder sourceBuilder = getSearchSourceBuilder(esDataQueryDtoList, sortParam, sortOrder);
        sourceBuilder.from((pageNo - 1) * pageSize);
        sourceBuilder.size(pageSize);
        executeQuery(indexName, indexType, list, pageInfo, clazz, sourceBuilder, null);
        return pageInfo;
    }

    private SearchSourceBuilder getSearchSourceBuilder(List<EsDataQueryDto> esDataQueryDtoList, String sortParam, SortOrder sortOrder) {
        SearchSourceBuilder sourceBuilder = new SearchSourceBuilder();
        if (Objects.isNull(sortOrder)) {
            sortOrder = SortOrder.DESC;
        }
        if (StringUtil.isNotEmpty(sortParam)) {
            FieldSortBuilder sort = SortBuilders.fieldSort(sortParam);
            sort.order(sortOrder);
            sourceBuilder.sort(sort);
        }
        BoolQueryBuilder boolQuery = QueryBuilders.boolQuery();
        for (EsDataQueryDto esDataQueryDto : esDataQueryDtoList) {
            String paramName = esDataQueryDto.getParamName();
            Object paramValue = esDataQueryDto.getParamValue();
            Date startTime = esDataQueryDto.getStartTime();
            Date endTime = esDataQueryDto.getEndTime();
            boolean analyse = esDataQueryDto.isAnalyse();

            if (Objects.nonNull(paramValue)) {
                if (paramValue instanceof Collection) {
                    if (analyse) {
                        BoolQueryBuilder builds = QueryBuilders.boolQuery();
                        Collection<?> collection = (Collection<?>) paramValue;
                        for (Object value : collection) {
                            builds.should(QueryBuilders.matchQuery(paramName, value));
                        }
                        boolQuery.must(builds);
                    } else {
                        QueryBuilder builds = QueryBuilders.termsQuery(paramName, (Collection<?>) paramValue);
                        boolQuery.must(builds);
                    }
                } else {
                    QueryBuilder builds;
                    if (analyse) {
                        builds = QueryBuilders.matchQuery(paramName, paramValue);
                    } else {
                        builds = QueryBuilders.termQuery(paramName, paramValue);
                    }
                    boolQuery.must(builds);
                }
            }
            if (Objects.nonNull(startTime) || Objects.nonNull(endTime)) {
                QueryBuilder builds = QueryBuilders.rangeQuery(paramName)
                        .from(startTime).to(endTime).includeLower(true);
                boolQuery.must(builds);
            }
        }
        sourceBuilder.trackTotalHits(true);
        sourceBuilder.query(boolQuery);
        return sourceBuilder;
    }

    /**
     * ES 查询执行核心：发送 DSL → 解析 response → 反序列化到 list/pageInfo。
     * 处理 ES 7.x+ total 格式差异（hits.total 从数字变为对象）。
     * 支持 highlight 回填和高亮字段替换、sort 值提取。
     */
    public <T> void executeQuery(String indexName, String indexType, List<T> list, PageInfo<T> pageInfo, Class<T> clazz,
                                 SearchSourceBuilder sourceBuilder, List<String> highLightFieldNameList) throws IOException {
        String string = sourceBuilder.toString();
        HttpEntity entity = new NStringEntity(string, ContentType.APPLICATION_JSON);
        StringBuilder endpointStringBuilder = new StringBuilder("/" + indexName);
        if (esTypeSwitch) {
            endpointStringBuilder.append("/").append(indexType).append("/_search");
        } else {
            endpointStringBuilder.append("/_search");
        }
        String endpoint = endpointStringBuilder.toString();
        log.info("query execute query dsl : {}", string);
        Response response = execute("POST", endpoint, entity);
        String result = EntityUtils.toString(response.getEntity());
        if (StringUtil.isEmpty(result)) {
            return;
        }
        JSONObject resultJsonObject = JSONObject.parseObject(result);
        if (Objects.isNull(resultJsonObject)) {
            return;
        }
        JSONObject hits = resultJsonObject.getJSONObject("hits");
        if (Objects.isNull(hits)) {
            return;
        }
        Long value = null;
        // ES 6.x: hits.total 直接为数字；ES 7.x+: hits.total 为 {value, relation} 对象
        if (esTypeSwitch) {
            value = hits.getLong("total");
        } else {
            JSONObject totalJsonObject = hits.getJSONObject("total");
            if (Objects.nonNull(totalJsonObject)) {
                value = totalJsonObject.getLong("value");
            }
        }
        if (Objects.nonNull(pageInfo) && Objects.nonNull(value)) {
            pageInfo.setTotal(value);
        }
        JSONArray arrayData = hits.getJSONArray("hits");
        if (Objects.isNull(arrayData) || arrayData.isEmpty()) {
            return;
        }
        for (int i = 0, size = arrayData.size(); i < size; i++) {
            JSONObject data = arrayData.getJSONObject(i);
            if (Objects.isNull(data)) {
                continue;
            }
            String esId = data.getString("_id");
            JSONObject jsonObject = data.getJSONObject("_source");
            if (Objects.isNull(jsonObject)) {
                continue;
            }
            // searchAfter 深分页：提取 sort 值并填充到返回实体中
            JSONArray jsonArray = data.getJSONArray("sort");
            if (Objects.nonNull(jsonArray) && !jsonArray.isEmpty()) {
                Object sortValue = jsonArray.get(0);
                if (sortValue instanceof Number) {
                    jsonObject.put("sort", ((Number) sortValue).longValue());
                }
            }
            // 高亮：用高亮片段替换原始字段值
            JSONObject highlight = data.getJSONObject("highlight");
            if (Objects.nonNull(highlight) && Objects.nonNull(highLightFieldNameList)) {
                for (String highLightFieldName : highLightFieldNameList) {
                    JSONArray highLightFieldValue = highlight.getJSONArray(highLightFieldName);
                    if (Objects.isNull(highLightFieldValue) || highLightFieldValue.isEmpty()) {
                        continue;
                    }
                    jsonObject.put(highLightFieldName, highLightFieldValue.get(0));
                }
            }
            if (StringUtil.isNotEmpty(esId)) {
                jsonObject.put("esId", esId);
            }
            list.add(JSONObject.parseObject(jsonObject.toJSONString(), clazz));
        }
    }

    public void deleteByDocumentId(String index, String documentId) {
        if (!esSwitch) {
            return;
        }
        try {
            Response response = execute("DELETE", "/" + index + "/_doc/" + documentId, null);
            log.info("deleteByDocumentId result : {}", response.getStatusLine().getReasonPhrase());
        } catch (Exception e) {
            log.error("deleteByDocumentId error", e);
        }
    }

    /**
     * 发送 REST 请求到 ES，统一 Request 构建逻辑。
     */
    private Response execute(String method, String path, HttpEntity entity) throws IOException {
        Request request = new Request(method, path);
        if (Objects.nonNull(entity)) {
            request.setEntity(entity);
        }
        return restClient.performRequest(request);
    }
}
