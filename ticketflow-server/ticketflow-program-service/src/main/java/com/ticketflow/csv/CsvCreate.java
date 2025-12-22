package com.ticketflow.csv;

import cn.hutool.core.io.FileUtil;
import com.opencsv.CSVWriter;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import lombok.extern.slf4j.Slf4j;

/**
 * CSV文件生成器。用于导出节目、订单等业务数据为CSV格式文件。
 */
@Slf4j
public class CsvCreate {
    
    private static final String CSV_FILE_LOCATION = Paths.get("").toAbsolutePath() + "/csv";
    
    private static final String CSV_FILE_NAME = CSV_FILE_LOCATION + "/ticketflow购买节目需要的压测数据.csv";
    
    static {
        if (!FileUtil.exist(CSV_FILE_LOCATION)) {
            FileUtil.mkdir(CSV_FILE_LOCATION);
        }
    }
    
    public static void main(String[] args) {
        createCsvFile();
    }
    
    public static void createCsvFile(){
        List<String[]> csvCompleteData = createCsvCompleteData();
        try (CSVWriter writer = new CSVWriter(new FileWriter(CSV_FILE_NAME, StandardCharsets.UTF_8))) {
            writer.writeAll(csvCompleteData);
        } catch (IOException e) {
            log.error("生成失败: {}", e.getMessage(), e);
        }
    }
    
    public static List<String[]> createCsvCompleteData() {
        List<String[]> csvData = new ArrayList<>();
        csvData.add(new String[]{"programId", "ticketCategoryId"});
        //节目id
        String programId = "34";
        Map<String, Integer> data = createData();
        for (final Entry<String, Integer> entry : data.entrySet()) {
            //节目票档id
            String ticketCategoryId = entry.getKey();
            //购买数量
            Integer purchaseQuantity = entry.getValue();
            for (int i = 1; i <= purchaseQuantity; i++) {
                csvData.add(new String[]{programId, ticketCategoryId});
            }
        }
        Collections.shuffle(csvData.subList(1, csvData.size()));
        return csvData;
    }
    
    /**
     * 生成数据
     * key: 节目票档id
     * value: 购买数量
     * */
    public static Map<String,Integer> createData(){
        Map<String,Integer> map = new HashMap<>(10);
        map.put("46",10000);
        map.put("45",10000);
        map.put("44",10000);
        map.put("43",15000);
        map.put("42",15000);
        map.put("41",20000);
        map.put("40",20000);
        return map;
    }
}
