package com.ticketflow.mapper;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ticketflow.entity.MessageProducerRecord;
import org.apache.ibatis.annotations.Delete;

/**
 * 消息发送记录表 Mapper
 */
public interface MessageProducerRecordMapper extends BaseMapper<MessageProducerRecord> {
    
    /** 
     * 删除所有记录 
     * @return Integer 结果
     * */
    @Delete("DELETE FROM d_message_producer_record")
    Integer delete();
}
