package com.ticketflow.mapper;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ticketflow.entity.MessageConsumerRecord;
import org.apache.ibatis.annotations.Delete;

/**
 * 消息消费记录表 Mapper
 */
public interface MessageConsumerRecordMapper extends BaseMapper<MessageConsumerRecord> {
    
    /**
     * 删除所有记录 
     * @return Integer 结果
     * */
    @Delete("DELETE FROM d_message_consumer_record")
    Integer delete();
}
