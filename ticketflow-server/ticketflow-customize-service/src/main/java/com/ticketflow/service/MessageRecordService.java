package com.ticketflow.service;


import cn.hutool.core.collection.CollectionUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ticketflow.dto.ExecuteExceptionMessageDto;
import com.ticketflow.dto.MessageRecordDto;
import com.ticketflow.entity.MessageConsumerRecord;
import com.ticketflow.entity.MessageProducerRecord;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.enums.MessageConsumerStatus;
import com.ticketflow.enums.MessageSendStatus;
import com.ticketflow.enums.MessageType;
import com.ticketflow.enums.ReconciliationStatus;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.handler.ExceptionMessageHandlerContext;
import com.ticketflow.mapper.MessageConsumerRecordMapper;
import com.ticketflow.mapper.MessageProducerRecordMapper;
import com.ticketflow.page.PageUtil;
import com.ticketflow.reconciliation.ReconciliationTask;
import com.ticketflow.reconciliation.ReconciliationTaskQueue;
import com.ticketflow.vo.MessageRecordVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 消息记录服务——可靠消息最终的查询/对账/异常处理层。
 *
 * 整合生产者消息和消费者消息，
 * 支持异常消息重试、执行异常处理方法、消息对账
 */
@Slf4j
@Service
public class MessageRecordService {
    
    @Autowired
    private MessageProducerRecordMapper messageProducerRecordMapper;
    
    @Autowired
    private MessageConsumerRecordMapper messageConsumerRecordMapper;
    
    @Autowired
    private ExceptionMessageHandlerContext exceptionMessageHandlerContext;
    
    @Autowired
    private ReconciliationTaskQueue reconciliationTaskQueue;
    
    @Autowired
    private MessageProducerRecordService messageProducerRecordService;
    
    
    public IPage<MessageRecordVo> page(MessageRecordDto messageRecordDto) {
        IPage<MessageRecordVo> messageRecordVoPage = new Page<>(messageRecordDto.getPageNumber(), messageRecordDto.getPageSize());
        // 查询生产者消息记录（分页）
        IPage<MessageProducerRecord> messageProducerRecordPage =
                messageProducerRecordMapper.selectPage(PageUtil.getPageParams(messageRecordDto.getPageNumber(),
                        messageRecordDto.getPageSize()), Wrappers.lambdaQuery(MessageProducerRecord.class)
                        .eq(MessageProducerRecord::getMessageBusinessesId, messageRecordDto.getMessageBusinessesId()));
        List<MessageProducerRecord> messageProducerRecordList = messageProducerRecordPage.getRecords();
        if (CollectionUtil.isEmpty(messageProducerRecordList)) {
            return messageRecordVoPage;
        }
        // 关联查询消费者消息记录（通过 messageId 1:N 关联，取最新的消费记录）
        List<MessageConsumerRecord> messageConsumerRecordList = 
                messageConsumerRecordMapper.selectList(Wrappers.lambdaQuery(MessageConsumerRecord.class)
                        .in(MessageConsumerRecord::getMessageId, messageProducerRecordList.stream()
                                .map(MessageProducerRecord::getMessageId).toList()));
        Map<Long, MessageConsumerRecord> messageConsumerRecordMap = messageConsumerRecordList.stream().collect(
                Collectors.toMap(MessageConsumerRecord::getMessageId, v -> v, 
                        (v1, v2) -> v2));
        // 合并生产者+消费者信息：发送状态 / 消费状态 / 对账状态 / 异常信息
        List<MessageRecordVo> messageRecordVoList = new ArrayList<>();
        for (MessageProducerRecord messageProducerRecord : messageProducerRecordList) {
            MessageRecordVo messageRecordVo = new MessageRecordVo();
            BeanUtils.copyProperties(messageProducerRecord, messageRecordVo);
            messageRecordVo.setMessageProducerRecordId(messageProducerRecord.getId());
            messageRecordVo.setMessageTypeName(MessageType.getMsg(messageProducerRecord.getMessageType()));
            messageRecordVo.setMessageSendStatusName(MessageSendStatus.getMsg(messageProducerRecord.getMessageSendStatus()));
            messageRecordVo.setReconciliationStatusName(ReconciliationStatus.getMsg(messageProducerRecord.getReconciliationStatus()));
            MessageConsumerRecord messageConsumerRecord = messageConsumerRecordMap.get(messageProducerRecord.getMessageId());
            if (Objects.nonNull(messageConsumerRecord)) {
                messageRecordVo.setMessageConsumerRecordId(messageConsumerRecord.getId());
                messageRecordVo.setMessageConsumerException(messageConsumerRecord.getMessageConsumerException());
                messageRecordVo.setMessageConsumerStatus(messageConsumerRecord.getMessageConsumerStatus());
                messageRecordVo.setMessageConsumerStatusName(MessageConsumerStatus.getMsg(messageConsumerRecord.getMessageConsumerStatus()));
                messageRecordVo.setMessageConsumerCount(messageConsumerRecord.getMessageConsumerCount());
                messageRecordVo.setConsumerTime(messageConsumerRecord.getConsumerTime());
            }
            messageRecordVoList.add(messageRecordVo);
        }
        BeanUtils.copyProperties(messageProducerRecordPage, messageRecordVoPage);
        messageRecordVoPage.setRecords(messageRecordVoList);
        return messageRecordVoPage;
    }
    
    public Boolean executeExceptionMessage(ExecuteExceptionMessageDto executeExceptionMessageDto) {
        LambdaQueryWrapper<MessageProducerRecord> wrapper = Wrappers.lambdaQuery(MessageProducerRecord.class);
        wrapper.eq(MessageProducerRecord::getMessageId, executeExceptionMessageDto.getMessageId());
        MessageProducerRecord existMessageProducerRecord = messageProducerRecordMapper.selectOne(wrapper);
        if (Objects.isNull(existMessageProducerRecord)) {
            throw new TicketFlowFrameException(BaseCode.MESSAGE_NOT_EXIST);
        }
        if (ReconciliationStatus.RECONCILIATION_SUCCESS.getCode().equals(existMessageProducerRecord.getReconciliationStatus())) {
            return true;
        }
        MessageType messageType = MessageType.getRc(existMessageProducerRecord.getMessageType());
        if (Objects.isNull(messageType)) {
            throw new TicketFlowFrameException(BaseCode.MESSAGE_TYPE_NOT_EXIST);
        }
        return exceptionMessageHandlerContext.getExceptionMessageHandler(messageType)
                .handle(existMessageProducerRecord);
    }
    
    public Boolean executeReconciliationTask() {
        log.info("执行消息记录的对账任务");
        // 遍历所有消息类型，每种类型独立处理异常消息
        for (MessageType messageType : MessageType.values()) {
            try {
                // 查询该类型下所有未对账的消息（发送时间超过合理等待期的）
                List<MessageProducerRecord> noReconciliationMessageProducerRecordList =
                        exceptionMessageHandlerContext.getExceptionMessageHandler(messageType).noReconciliationMessageProducerRecordList();
                if (CollectionUtil.isEmpty(noReconciliationMessageProducerRecordList)) {
                    continue;
                }
                Map<Long, MessageConsumerRecord> messageConsumerRecordMap = getMessageConsumerRecordMap(noReconciliationMessageProducerRecordList.stream()
                        .map(MessageProducerRecord::getMessageId).toList());
                
                for (MessageProducerRecord messageProducerRecord : noReconciliationMessageProducerRecordList){
                    MessageConsumerRecord messageConsumerRecord =
                            messageConsumerRecordMap.get(messageProducerRecord.getMessageId());
                    // 未消费或消费失败 → 入重试队列（通过 ExceptionMessageHandler 重新发送）
                    if (Objects.isNull(messageConsumerRecord) ||
                            messageConsumerRecord.getMessageConsumerStatus().equals(MessageConsumerStatus.UNCONSUMED.getCode()) ||
                            messageConsumerRecord.getMessageConsumerStatus().equals(MessageConsumerStatus.CONSUMER_FAIL.getCode())) {
                        ReconciliationTask reconciliationTask = () -> {
                            exceptionMessageHandlerContext.getExceptionMessageHandler(messageType).handle(messageProducerRecord);
                        };
                        reconciliationTaskQueue.putTask(reconciliationTask);
                    }else {
                        // 发送成功 + 消费成功 → 标记对账成功
                        Integer messageSendStatus = messageProducerRecord.getMessageSendStatus();
                        Integer messageConsumerStatus = messageConsumerRecord.getMessageConsumerStatus();
                        if (messageSendStatus.equals(MessageSendStatus.SEND_SUCCESS.getCode()) &&
                                messageConsumerStatus.equals(MessageConsumerStatus.CONSUMER_SUCCESS.getCode())) {
                            messageProducerRecordService.updateToReconciliationSuccess(messageProducerRecord,messageConsumerRecord);
                        }
                    }
                }
            }catch (Exception e){
                log.error("executeReconciliationTask error",e);
            }
        }
        return true;
    }
    
    /**
     * 查询对应的消息消费记录
     * */
    public Map<Long, MessageConsumerRecord> getMessageConsumerRecordMap(List<Long> messageIdList){
        LambdaQueryWrapper<MessageConsumerRecord> messageConsumerRecordWrapper = Wrappers.lambdaQuery(MessageConsumerRecord.class);
        messageConsumerRecordWrapper.in(MessageConsumerRecord::getMessageId, messageIdList);
        List<MessageConsumerRecord> messageConsumerRecordList = messageConsumerRecordMapper.selectList(messageConsumerRecordWrapper);
        return messageConsumerRecordList.stream().collect(Collectors.toMap(MessageConsumerRecord::getMessageId, m -> m));
    }
    
    @Transactional(rollbackFor = Exception.class)
    public void deleteMessageRecord(Date date){
        //把之前的消息记录数据删除掉，真实环境中不会删除的，这里是为了在线演示才删除的，要不然数据太多了
        messageProducerRecordMapper.delete();
        messageConsumerRecordMapper.delete();
    }
}
