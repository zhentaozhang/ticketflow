-- 支付服务增量 DDL：对已按 1_ticketflow_cloud_create_database.sql 部署过的库执行。
-- 新部署直接使用更新后的 ticketflow_pay_0.sql / ticketflow_pay_1.sql，无需执行本脚本。
-- 变更内容：
--   1. 金额字段精度 decimal(10,0) → decimal(10,2)（修复分单位金额无法入库的问题）
--   2. d_refund_bill 新增 out_refund_no（退款单号，渠道幂等键/退款查询依据），退款唯一键由 out_order_no 改为 out_refund_no
--      （支持同一订单多次部分退款；存量行 out_refund_no 为 NULL 不参与唯一约束）

USE ticketflow_pay_0;

ALTER TABLE d_pay_bill_0 MODIFY COLUMN `pay_amount` decimal(10,2) NOT NULL COMMENT '支付金额';
ALTER TABLE d_refund_bill_0 MODIFY COLUMN `refund_amount` decimal(10,2) NOT NULL COMMENT '退款金额';
ALTER TABLE d_refund_bill_0 DROP INDEX `d_refund_bill_out_order_no_IDX`;
ALTER TABLE d_refund_bill_0 ADD COLUMN `out_refund_no` varchar(64) DEFAULT NULL COMMENT '退款单号（渠道幂等键、退款查询依据）' AFTER `pay_bill_id`;
ALTER TABLE d_refund_bill_0 ADD UNIQUE KEY `d_refund_bill_out_refund_no_IDX` (`out_refund_no`) USING BTREE;
ALTER TABLE d_refund_bill_0 MODIFY COLUMN `refund_status` int(11) NOT NULL DEFAULT '1' COMMENT '账单退款状态 1：退款处理中 2：已退款 3：退款失败';

ALTER TABLE d_pay_bill_1 MODIFY COLUMN `pay_amount` decimal(10,2) NOT NULL COMMENT '支付金额';
ALTER TABLE d_refund_bill_1 MODIFY COLUMN `refund_amount` decimal(10,2) NOT NULL COMMENT '退款金额';
ALTER TABLE d_refund_bill_1 DROP INDEX `d_refund_bill_out_order_no_IDX`;
ALTER TABLE d_refund_bill_1 ADD COLUMN `out_refund_no` varchar(64) DEFAULT NULL COMMENT '退款单号（渠道幂等键、退款查询依据）' AFTER `pay_bill_id`;
ALTER TABLE d_refund_bill_1 ADD UNIQUE KEY `d_refund_bill_out_refund_no_IDX` (`out_refund_no`) USING BTREE;
ALTER TABLE d_refund_bill_1 MODIFY COLUMN `refund_status` int(11) NOT NULL DEFAULT '1' COMMENT '账单退款状态 1：退款处理中 2：已退款 3：退款失败';

USE ticketflow_pay_1;

ALTER TABLE d_pay_bill_0 MODIFY COLUMN `pay_amount` decimal(10,2) NOT NULL COMMENT '支付金额';
ALTER TABLE d_refund_bill_0 MODIFY COLUMN `refund_amount` decimal(10,2) NOT NULL COMMENT '退款金额';
ALTER TABLE d_refund_bill_0 DROP INDEX `d_refund_bill_out_order_no_IDX`;
ALTER TABLE d_refund_bill_0 ADD COLUMN `out_refund_no` varchar(64) DEFAULT NULL COMMENT '退款单号（渠道幂等键、退款查询依据）' AFTER `pay_bill_id`;
ALTER TABLE d_refund_bill_0 ADD UNIQUE KEY `d_refund_bill_out_refund_no_IDX` (`out_refund_no`) USING BTREE;
ALTER TABLE d_refund_bill_0 MODIFY COLUMN `refund_status` int(11) NOT NULL DEFAULT '1' COMMENT '账单退款状态 1：退款处理中 2：已退款 3：退款失败';

ALTER TABLE d_pay_bill_1 MODIFY COLUMN `pay_amount` decimal(10,2) NOT NULL COMMENT '支付金额';
ALTER TABLE d_refund_bill_1 MODIFY COLUMN `refund_amount` decimal(10,2) NOT NULL COMMENT '退款金额';
ALTER TABLE d_refund_bill_1 DROP INDEX `d_refund_bill_out_order_no_IDX`;
ALTER TABLE d_refund_bill_1 ADD COLUMN `out_refund_no` varchar(64) DEFAULT NULL COMMENT '退款单号（渠道幂等键、退款查询依据）' AFTER `pay_bill_id`;
ALTER TABLE d_refund_bill_1 ADD UNIQUE KEY `d_refund_bill_out_refund_no_IDX` (`out_refund_no`) USING BTREE;
ALTER TABLE d_refund_bill_1 MODIFY COLUMN `refund_status` int(11) NOT NULL DEFAULT '1' COMMENT '账单退款状态 1：退款处理中 2：已退款 3：退款失败';
