-- =============================================================================
-- 增量脚本：消息发送记录表补「自动重投次数」与「下次重投时间」
-- 适用：已经按旧版 ticketflow_customize.sql 建过表的库（新部署直接用新版建表脚本，无需执行本脚本）
--
-- 先执行本脚本再发布 customize-service，否则消息对账任务查询
-- d_message_producer_record.retry_count / next_retry_time 会因缺列失败，
-- 对账整轮被 catch 吞掉（只留一行 executeReconciliationTask error 日志）。
-- =============================================================================

USE ticketflow_customize;

ALTER TABLE d_message_producer_record
    ADD COLUMN retry_count int NOT NULL DEFAULT '0' COMMENT '自动重投次数，达到上限后转对账有问题等待人工处理',
    ADD COLUMN next_retry_time datetime DEFAULT NULL COMMENT '下次允许重投的时间（指数退避）',
    ADD INDEX d_message_producer_record_retry_IDX (reconciliation_status, next_retry_time) USING BTREE;
