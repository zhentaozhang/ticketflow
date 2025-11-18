/*
 * Copyright (c) 2017 Baidu, Inc. All Rights Reserve.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.baidu.fsg.uid;

import com.baidu.fsg.uid.exception.UidGenerateException;

/**
 * Represents a unique id generator.
 *
 * @author yutianbao
 */
public interface UidGenerator {

    /**
     * Get a unique ID
     *
     * @return UID
     * @throws UidGenerateException
     */
    long getUid() throws UidGenerateException;
    
    /**
     * 获取id
     * @return 结果
     * */
    long getId();
    
    /**
     * 获取订单编号
     * @param userId 用户id
     * @param tableCount 分表数量
     * @param databaseCount 分库数量
     * @return 结果
     * */
    long getOrderNumber(long userId,long tableCount,long databaseCount);
    
    /**
     * 【方案1】获取订单编号 - 固定预留6位基因位
     * 核心思想：预留足够多的基因位，支持未来扩容而无需修改生成逻辑
     * 6位基因支持最多64种分片组合（8库8表 或 4库16表）
     * 扩容时只需修改分片算法配置，无需修改此方法
     * @param userId 用户id
     * @return 结果
     * */
    long getOrderNumber(long userId);
    
    /**
     * 获取订单编号 （兼容旧版本的方法仅包含表基因）
     * @deprecated 建议使用 getOrderNumber(userId, tableCount, databaseCount)
     * @param userId 用户id
     * @param tableCount 分表数量
     * @return 结果
     * */
    @Deprecated
    long getOrderNumber(long userId,long tableCount);

    /**
     * Parse the UID into elements which are used to generate the UID. <br>
     * Such as timestamp & workerId & sequence...
     *
     * @param uid
     * @return Parsed info
     */
    String parseUid(long uid);

}
