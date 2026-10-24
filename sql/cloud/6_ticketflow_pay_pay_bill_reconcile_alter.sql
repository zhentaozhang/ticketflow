
-- 存量库升级脚本（新部署直接用更新后的 ticketflow_pay_0.sql / ticketflow_pay_1.sql，无需执行本脚本）
--
-- 背景：pay-service 新增「未支付账单主动查单」任务，按 pay_bill_status + pay_time 范围扫表；
-- 没有这个索引就是 16 张物理表全扫，且扫的是热点表 d_pay_bill。

USE ticketflow_pay_0;

ALTER TABLE `d_pay_bill_0` ADD INDEX `d_pay_bill_status_time_IDX` (`pay_bill_status`, `pay_time`) USING BTREE;
ALTER TABLE `d_pay_bill_1` ADD INDEX `d_pay_bill_status_time_IDX` (`pay_bill_status`, `pay_time`) USING BTREE;

USE ticketflow_pay_1;

ALTER TABLE `d_pay_bill_0` ADD INDEX `d_pay_bill_status_time_IDX` (`pay_bill_status`, `pay_time`) USING BTREE;
ALTER TABLE `d_pay_bill_1` ADD INDEX `d_pay_bill_status_time_IDX` (`pay_bill_status`, `pay_time`) USING BTREE;
