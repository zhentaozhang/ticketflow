package com.ticketflow.service.delayconsumer;

import com.alibaba.fastjson.JSON;
import com.ticketflow.core.ConsumerTask;
import com.ticketflow.core.SpringUtil;
import com.ticketflow.dto.ProgramOperateDataDto;
import com.ticketflow.service.ProgramService;
import com.ticketflow.util.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import static com.ticketflow.constant.ProgramOrderConstant.DELAY_OPERATE_PROGRAM_DATA_TOPIC;

/**
 * 延迟队列消费者：节目数据操作。
 * 消费来自 DELAY_OPERATE_PROGRAM_DATA_TOPIC 的消息，
 * 调用 ProgramService.operateProgramData() 执行座位状态变更
 * （取消时解锁座位+恢复余票，支付时标记已售）。
 *
 * 与 DelayOrderCancelSend 配对使用——发送端推送延迟消息，
 * 到达时间后此消费者执行实际数据操作
 */
@Slf4j
@Component
public class DelayOperateProgramDataConsumer implements ConsumerTask {
    
    @Autowired
    private ProgramService programService;
    
    /**
     * 消费延迟队列消息，执行节目数据操作（取消时解锁座位+恢复余票，支付时标记已售）。
     *
     * @param content JSON 格式的 ProgramOperateDataDto 消息内容
     */
    @Override
    public void execute(String content) {
        log.info("延迟操作节目数据消息进行消费 content : {}", content);
        if (StringUtil.isEmpty(content)) {
            log.error("延迟队列消息不存在");
            return;
        }
        ProgramOperateDataDto programOperateDataDto = JSON.parseObject(content, ProgramOperateDataDto.class);
        programService.operateProgramData(programOperateDataDto);
    }
    
    @Override
    public String topic() {
        return SpringUtil.getPrefixDistinctionName() + "-" + DELAY_OPERATE_PROGRAM_DATA_TOPIC;
    }
}
