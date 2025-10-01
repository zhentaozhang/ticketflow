package com.ticketflow.util;

import static com.ticketflow.constant.Constant.GLIDE_LINE;

/**
 * 字符串分割工具（按下划线 _ 切分）。
 * 用于解析 ShardingSphere 分库分表中的逻辑表名与真实表名
 */
public class SplitUtil {
    
    public static String[] toSplit(String str) {
        return str.split(GLIDE_LINE);
    }
}
