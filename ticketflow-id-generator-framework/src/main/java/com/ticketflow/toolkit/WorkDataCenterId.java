package com.ticketflow.toolkit;

import lombok.Data;

/**
 * WorkId和DataCenterId值对象。封装雪花算法所需的机器ID和数据中心ID。
 */
@Data
public class WorkDataCenterId {

    private Long workId;
    
    private Long dataCenterId;
}
