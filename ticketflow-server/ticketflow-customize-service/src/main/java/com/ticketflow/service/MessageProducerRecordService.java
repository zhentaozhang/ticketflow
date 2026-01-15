package com.ticketflow.service;


import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ticketflow.dto.InsertMessageProducerRecordDto;
import com.ticketflow.dto.UpdateMessageProducerRecordDto;
import com.ticketflow.entity.MessageConsumerRecord;
import com.ticketflow.entity.MessageProducerRecord;
import com.ticketflow.enums.MessageSendStatus;
import com.ticketflow.enums.ReconciliationStatus;
import com.ticketflow.mapper.MessageConsumerRecordMapper;
import com.ticketflow.mapper.MessageProducerRecordMapper;
import com.ticketflow.util.DateUtils;
import com.ticketflow.util.StringUtil;
import com.ticketflow.vo.MessageProducerRecordVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 消息生产者记录服务——管理可靠消息的发送记录。
 *
 * 记录消息的发送状态、重试次数，
 * 支持预插入、确认发送、状态更新等事务性操作
 */
@Slf4j
@Service
public class MessageProducerRecordService extends ServiceImpl<MessageProducerRecordMapper, MessageProducerRecord> {
    
    @Autowired
    private UidGenerator uidGenerator;
    
    @Autowired
    private MessageProducerRecordMapper messageProducerRecordMapper;
    
    @Autowired
    private MessageConsumerRecordMapper messageConsumerRecordMapper;
    
    
    public MessageProducerRecord getMessageProducerRecordByMessageId(Long messageId) {
        LambdaQueryWrapper<MessageProducerRecord> wrapper = Wrappers.lambdaQuery(MessageProducerRecord.class);
        wrapper.eq(MessageProducerRecord::getMessageId, messageId);
        return messageProducerRecordMapper.selectOne(wrapper);
    }
    
    
    @Transactional(rollbackFor = Exception.class)
    public MessageProducerRecordVo insertMessageProducerRecord(InsertMessageProducerRecordDto insertMessageProducerRecordDto){
        MessageProducerRecord messageProducerRecord = new MessageProducerRecord();
        BeanUtils.copyProperties(insertMessageProducerRecordDto, messageProducerRecord);
        messageProducerRecord.setId(uidGenerator.getUid());
        messageProducerRecord.setMessageType(insertMessageProducerRecordDto.getMessageType());
        messageProducerRecord.setMessageTraceId(insertMessageProducerRecordDto.getMessageTraceId());
        messageProducerRecord.setMessageBusinessesId(insertMessageProducerRecordDto.getMessageBusinessesId());
        messageProducerRecord.setMessageId(insertMessageProducerRecordDto.getMessageId());
        messageProducerRecord.setMessageTopic(insertMessageProducerRecordDto.getMessageTopic());
        messageProducerRecord.setMessageContent(insertMessageProducerRecordDto.getMessageContent());
        messageProducerRecord.setMessageSendStatus(MessageSendStatus.UNSENT.getCode());
        messageProducerRecord.setReconciliationStatus(ReconciliationStatus.RECONCILIATION_NO.getCode());
        messageProducerRecord.setSendTime(DateUtils.now());
        messageProducerRecordMapper.insert(messageProducerRecord);
        MessageProducerRecordVo messageProducerRecordVo = new MessageProducerRecordVo();
        BeanUtils.copyProperties(messageProducerRecord, messageProducerRecordVo);
        return messageProducerRecordVo;
    }
    
    public Boolean updateMessageProducerRecord(UpdateMessageProducerRecordDto updateMessageProducerRecordDto) {
        MessageProducerRecord udpateMessageProducerRecord = new MessageProducerRecord();
        BeanUtils.copyProperties(updateMessageProducerRecordDto,udpateMessageProducerRecord);
        if (StringUtil.isEmpty(udpateMessageProducerRecord.getMessageContent())) {
            udpateMessageProducerRecord.setMessageContent(null);
        }
        if (StringUtil.isEmpty(udpateMessageProducerRecord.getMessageSendException())) {
            udpateMessageProducerRecord.setMessageSendException(null);
        }
        return updateById(udpateMessageProducerRecord);
    }
    
    @Transactional(rollbackFor = Exception.class)
    public void updateToReconciliationSuccess(MessageProducerRecord oldMessageProducerRecord, MessageConsumerRecord oldMessageConsumerRecord){
        MessageProducerRecord updateMessageProducerRecord = new MessageProducerRecord();
        updateMessageProducerRecord.setId(oldMessageProducerRecord.getId());
        updateMessageProducerRecord.setReconciliationStatus(ReconciliationStatus.RECONCILIATION_SUCCESS.getCode());
        updateById(updateMessageProducerRecord);
        MessageConsumerRecord updateMessageConsumerRecord = new MessageConsumerRecord();
        updateMessageConsumerRecord.setId(oldMessageConsumerRecord.getId());
        updateMessageConsumerRecord.setReconciliationStatus(ReconciliationStatus.RECONCILIATION_SUCCESS.getCode());
        messageConsumerRecordMapper.updateById(updateMessageConsumerRecord);
    }
}
