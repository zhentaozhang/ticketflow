-- 订单库增量 DDL：为 d_order 增加退款完成时间。
-- 新部署直接使用更新后的 sql/cloud/ticketflow_order_*.sql，无需执行本脚本。
--
-- 背景：退款改单（渠道已收款而本地订单已取消 → 退款并置已退单）原来只 update 主表
-- order_status=4，没有退款时间、没有子表更新、没有对账标记。补 refund_order_time
-- 让资金侧能按「退款发生时间」对账，也让「已退单」这一终态有可追溯的时间戳。
--
-- 执行顺序：先执行本脚本，再发布带退款收尾代码的 order-service；
-- 未执行就发布，退款收尾更新会因缺少列而失败（退款回调直接报错）。

-- ==================== ticketflow_order_0 ====================
USE ticketflow_order_0;
ALTER TABLE d_order_0 ADD COLUMN `refund_order_time` datetime DEFAULT NULL COMMENT '退款订单时间';
ALTER TABLE d_order_1 ADD COLUMN `refund_order_time` datetime DEFAULT NULL COMMENT '退款订单时间';
ALTER TABLE d_order_2 ADD COLUMN `refund_order_time` datetime DEFAULT NULL COMMENT '退款订单时间';
ALTER TABLE d_order_3 ADD COLUMN `refund_order_time` datetime DEFAULT NULL COMMENT '退款订单时间';
ALTER TABLE d_order_4 ADD COLUMN `refund_order_time` datetime DEFAULT NULL COMMENT '退款订单时间';
ALTER TABLE d_order_5 ADD COLUMN `refund_order_time` datetime DEFAULT NULL COMMENT '退款订单时间';
ALTER TABLE d_order_6 ADD COLUMN `refund_order_time` datetime DEFAULT NULL COMMENT '退款订单时间';
ALTER TABLE d_order_7 ADD COLUMN `refund_order_time` datetime DEFAULT NULL COMMENT '退款订单时间';


-- ==================== ticketflow_order_1 ====================
USE ticketflow_order_1;
ALTER TABLE d_order_0 ADD COLUMN `refund_order_time` datetime DEFAULT NULL COMMENT '退款订单时间';
ALTER TABLE d_order_1 ADD COLUMN `refund_order_time` datetime DEFAULT NULL COMMENT '退款订单时间';
ALTER TABLE d_order_2 ADD COLUMN `refund_order_time` datetime DEFAULT NULL COMMENT '退款订单时间';
ALTER TABLE d_order_3 ADD COLUMN `refund_order_time` datetime DEFAULT NULL COMMENT '退款订单时间';
ALTER TABLE d_order_4 ADD COLUMN `refund_order_time` datetime DEFAULT NULL COMMENT '退款订单时间';
ALTER TABLE d_order_5 ADD COLUMN `refund_order_time` datetime DEFAULT NULL COMMENT '退款订单时间';
ALTER TABLE d_order_6 ADD COLUMN `refund_order_time` datetime DEFAULT NULL COMMENT '退款订单时间';
ALTER TABLE d_order_7 ADD COLUMN `refund_order_time` datetime DEFAULT NULL COMMENT '退款订单时间';
