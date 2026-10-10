-- 订单/节目/用户服务增量 DDL：对已按 1_ticketflow_cloud_create_database.sql 部署过的库执行。
-- 新部署直接使用更新后的 ticketflow_order_*.sql / ticketflow_program_*.sql / ticketflow_user_*.sql，无需执行本脚本。
-- 变更内容：
--   1. 金额字段精度 decimal(10,0) → decimal(10,2)（修复分/角金额被截断的问题）：
--      d_order_*.order_price、d_order_ticket_user_*.order_price/pay_order_price、
--      d_order_ticket_user_record_*.order_price、d_seat_*.price、d_ticket_category_*.price
--   2. d_seat_* 去掉 AUTO_INCREMENT：分片主键统一由分布式 ID 生成器生成，避免分片间主键碰撞
--   3. d_ticket_user_* 增加 user_id 索引：购票人预检/列表按 user_id 高频查询，避免全表扫描

-- ==================== 订单库 ====================
USE ticketflow_order_0;
ALTER TABLE d_order_0 MODIFY COLUMN `order_price` decimal(10,2) DEFAULT NULL COMMENT '订单价格';
ALTER TABLE d_order_1 MODIFY COLUMN `order_price` decimal(10,2) DEFAULT NULL COMMENT '订单价格';
ALTER TABLE d_order_2 MODIFY COLUMN `order_price` decimal(10,2) DEFAULT NULL COMMENT '订单价格';
ALTER TABLE d_order_3 MODIFY COLUMN `order_price` decimal(10,2) DEFAULT NULL COMMENT '订单价格';
ALTER TABLE d_order_4 MODIFY COLUMN `order_price` decimal(10,2) DEFAULT NULL COMMENT '订单价格';
ALTER TABLE d_order_5 MODIFY COLUMN `order_price` decimal(10,2) DEFAULT NULL COMMENT '订单价格';
ALTER TABLE d_order_6 MODIFY COLUMN `order_price` decimal(10,2) DEFAULT NULL COMMENT '订单价格';
ALTER TABLE d_order_7 MODIFY COLUMN `order_price` decimal(10,2) DEFAULT NULL COMMENT '订单价格';
ALTER TABLE d_order_ticket_user_0 MODIFY COLUMN `order_price` decimal(10,2) DEFAULT NULL COMMENT '订单价格';
ALTER TABLE d_order_ticket_user_0 MODIFY COLUMN `pay_order_price` decimal(10,2) DEFAULT NULL COMMENT '支付订单价格';
ALTER TABLE d_order_ticket_user_1 MODIFY COLUMN `order_price` decimal(10,2) DEFAULT NULL COMMENT '订单价格';
ALTER TABLE d_order_ticket_user_1 MODIFY COLUMN `pay_order_price` decimal(10,2) DEFAULT NULL COMMENT '支付订单价格';
ALTER TABLE d_order_ticket_user_2 MODIFY COLUMN `order_price` decimal(10,2) DEFAULT NULL COMMENT '订单价格';
ALTER TABLE d_order_ticket_user_2 MODIFY COLUMN `pay_order_price` decimal(10,2) DEFAULT NULL COMMENT '支付订单价格';
ALTER TABLE d_order_ticket_user_3 MODIFY COLUMN `order_price` decimal(10,2) DEFAULT NULL COMMENT '订单价格';
ALTER TABLE d_order_ticket_user_3 MODIFY COLUMN `pay_order_price` decimal(10,2) DEFAULT NULL COMMENT '支付订单价格';
ALTER TABLE d_order_ticket_user_4 MODIFY COLUMN `order_price` decimal(10,2) DEFAULT NULL COMMENT '订单价格';
ALTER TABLE d_order_ticket_user_4 MODIFY COLUMN `pay_order_price` decimal(10,2) DEFAULT NULL COMMENT '支付订单价格';
ALTER TABLE d_order_ticket_user_5 MODIFY COLUMN `order_price` decimal(10,2) DEFAULT NULL COMMENT '订单价格';
ALTER TABLE d_order_ticket_user_5 MODIFY COLUMN `pay_order_price` decimal(10,2) DEFAULT NULL COMMENT '支付订单价格';
ALTER TABLE d_order_ticket_user_6 MODIFY COLUMN `order_price` decimal(10,2) DEFAULT NULL COMMENT '订单价格';
ALTER TABLE d_order_ticket_user_6 MODIFY COLUMN `pay_order_price` decimal(10,2) DEFAULT NULL COMMENT '支付订单价格';
ALTER TABLE d_order_ticket_user_7 MODIFY COLUMN `order_price` decimal(10,2) DEFAULT NULL COMMENT '订单价格';
ALTER TABLE d_order_ticket_user_7 MODIFY COLUMN `pay_order_price` decimal(10,2) DEFAULT NULL COMMENT '支付订单价格';
ALTER TABLE d_order_ticket_user_record_0 MODIFY COLUMN `order_price` decimal(10,2) DEFAULT NULL COMMENT '订单价格';
ALTER TABLE d_order_ticket_user_record_1 MODIFY COLUMN `order_price` decimal(10,2) DEFAULT NULL COMMENT '订单价格';
ALTER TABLE d_order_ticket_user_record_2 MODIFY COLUMN `order_price` decimal(10,2) DEFAULT NULL COMMENT '订单价格';
ALTER TABLE d_order_ticket_user_record_3 MODIFY COLUMN `order_price` decimal(10,2) DEFAULT NULL COMMENT '订单价格';
ALTER TABLE d_order_ticket_user_record_4 MODIFY COLUMN `order_price` decimal(10,2) DEFAULT NULL COMMENT '订单价格';
ALTER TABLE d_order_ticket_user_record_5 MODIFY COLUMN `order_price` decimal(10,2) DEFAULT NULL COMMENT '订单价格';
ALTER TABLE d_order_ticket_user_record_6 MODIFY COLUMN `order_price` decimal(10,2) DEFAULT NULL COMMENT '订单价格';
ALTER TABLE d_order_ticket_user_record_7 MODIFY COLUMN `order_price` decimal(10,2) DEFAULT NULL COMMENT '订单价格';

USE ticketflow_order_1;
ALTER TABLE d_order_0 MODIFY COLUMN `order_price` decimal(10,2) DEFAULT NULL COMMENT '订单价格';
ALTER TABLE d_order_1 MODIFY COLUMN `order_price` decimal(10,2) DEFAULT NULL COMMENT '订单价格';
ALTER TABLE d_order_2 MODIFY COLUMN `order_price` decimal(10,2) DEFAULT NULL COMMENT '订单价格';
ALTER TABLE d_order_3 MODIFY COLUMN `order_price` decimal(10,2) DEFAULT NULL COMMENT '订单价格';
ALTER TABLE d_order_4 MODIFY COLUMN `order_price` decimal(10,2) DEFAULT NULL COMMENT '订单价格';
ALTER TABLE d_order_5 MODIFY COLUMN `order_price` decimal(10,2) DEFAULT NULL COMMENT '订单价格';
ALTER TABLE d_order_6 MODIFY COLUMN `order_price` decimal(10,2) DEFAULT NULL COMMENT '订单价格';
ALTER TABLE d_order_7 MODIFY COLUMN `order_price` decimal(10,2) DEFAULT NULL COMMENT '订单价格';
ALTER TABLE d_order_ticket_user_0 MODIFY COLUMN `order_price` decimal(10,2) DEFAULT NULL COMMENT '订单价格';
ALTER TABLE d_order_ticket_user_0 MODIFY COLUMN `pay_order_price` decimal(10,2) DEFAULT NULL COMMENT '支付订单价格';
ALTER TABLE d_order_ticket_user_1 MODIFY COLUMN `order_price` decimal(10,2) DEFAULT NULL COMMENT '订单价格';
ALTER TABLE d_order_ticket_user_1 MODIFY COLUMN `pay_order_price` decimal(10,2) DEFAULT NULL COMMENT '支付订单价格';
ALTER TABLE d_order_ticket_user_2 MODIFY COLUMN `order_price` decimal(10,2) DEFAULT NULL COMMENT '订单价格';
ALTER TABLE d_order_ticket_user_2 MODIFY COLUMN `pay_order_price` decimal(10,2) DEFAULT NULL COMMENT '支付订单价格';
ALTER TABLE d_order_ticket_user_3 MODIFY COLUMN `order_price` decimal(10,2) DEFAULT NULL COMMENT '订单价格';
ALTER TABLE d_order_ticket_user_3 MODIFY COLUMN `pay_order_price` decimal(10,2) DEFAULT NULL COMMENT '支付订单价格';
ALTER TABLE d_order_ticket_user_4 MODIFY COLUMN `order_price` decimal(10,2) DEFAULT NULL COMMENT '订单价格';
ALTER TABLE d_order_ticket_user_4 MODIFY COLUMN `pay_order_price` decimal(10,2) DEFAULT NULL COMMENT '支付订单价格';
ALTER TABLE d_order_ticket_user_5 MODIFY COLUMN `order_price` decimal(10,2) DEFAULT NULL COMMENT '订单价格';
ALTER TABLE d_order_ticket_user_5 MODIFY COLUMN `pay_order_price` decimal(10,2) DEFAULT NULL COMMENT '支付订单价格';
ALTER TABLE d_order_ticket_user_6 MODIFY COLUMN `order_price` decimal(10,2) DEFAULT NULL COMMENT '订单价格';
ALTER TABLE d_order_ticket_user_6 MODIFY COLUMN `pay_order_price` decimal(10,2) DEFAULT NULL COMMENT '支付订单价格';
ALTER TABLE d_order_ticket_user_7 MODIFY COLUMN `order_price` decimal(10,2) DEFAULT NULL COMMENT '订单价格';
ALTER TABLE d_order_ticket_user_7 MODIFY COLUMN `pay_order_price` decimal(10,2) DEFAULT NULL COMMENT '支付订单价格';
ALTER TABLE d_order_ticket_user_record_0 MODIFY COLUMN `order_price` decimal(10,2) DEFAULT NULL COMMENT '订单价格';
ALTER TABLE d_order_ticket_user_record_1 MODIFY COLUMN `order_price` decimal(10,2) DEFAULT NULL COMMENT '订单价格';
ALTER TABLE d_order_ticket_user_record_2 MODIFY COLUMN `order_price` decimal(10,2) DEFAULT NULL COMMENT '订单价格';
ALTER TABLE d_order_ticket_user_record_3 MODIFY COLUMN `order_price` decimal(10,2) DEFAULT NULL COMMENT '订单价格';
ALTER TABLE d_order_ticket_user_record_4 MODIFY COLUMN `order_price` decimal(10,2) DEFAULT NULL COMMENT '订单价格';
ALTER TABLE d_order_ticket_user_record_5 MODIFY COLUMN `order_price` decimal(10,2) DEFAULT NULL COMMENT '订单价格';
ALTER TABLE d_order_ticket_user_record_6 MODIFY COLUMN `order_price` decimal(10,2) DEFAULT NULL COMMENT '订单价格';
ALTER TABLE d_order_ticket_user_record_7 MODIFY COLUMN `order_price` decimal(10,2) DEFAULT NULL COMMENT '订单价格';

-- ==================== 节目库 ====================
USE ticketflow_program_0;
ALTER TABLE d_seat_0 MODIFY COLUMN `id` bigint NOT NULL COMMENT '主键id';
ALTER TABLE d_seat_1 MODIFY COLUMN `id` bigint NOT NULL COMMENT '主键id';
ALTER TABLE d_seat_0 MODIFY COLUMN `price` decimal(10,2) NOT NULL COMMENT '座位价格';
ALTER TABLE d_seat_1 MODIFY COLUMN `price` decimal(10,2) NOT NULL COMMENT '座位价格';
ALTER TABLE d_ticket_category_0 MODIFY COLUMN `price` decimal(10,2) NOT NULL COMMENT '价格';
ALTER TABLE d_ticket_category_1 MODIFY COLUMN `price` decimal(10,2) NOT NULL COMMENT '价格';

USE ticketflow_program_1;
ALTER TABLE d_seat_0 MODIFY COLUMN `id` bigint NOT NULL COMMENT '主键id';
ALTER TABLE d_seat_1 MODIFY COLUMN `id` bigint NOT NULL COMMENT '主键id';
ALTER TABLE d_seat_0 MODIFY COLUMN `price` decimal(10,2) NOT NULL COMMENT '座位价格';
ALTER TABLE d_seat_1 MODIFY COLUMN `price` decimal(10,2) NOT NULL COMMENT '座位价格';
ALTER TABLE d_ticket_category_0 MODIFY COLUMN `price` decimal(10,2) NOT NULL COMMENT '价格';
ALTER TABLE d_ticket_category_1 MODIFY COLUMN `price` decimal(10,2) NOT NULL COMMENT '价格';

-- ==================== 用户库 ====================
USE ticketflow_user_0;
ALTER TABLE d_ticket_user_0 ADD KEY `d_ticket_user_user_id_IDX` (`user_id`) USING BTREE;
ALTER TABLE d_ticket_user_1 ADD KEY `d_ticket_user_user_id_IDX` (`user_id`) USING BTREE;

USE ticketflow_user_1;
ALTER TABLE d_ticket_user_0 ADD KEY `d_ticket_user_user_id_IDX` (`user_id`) USING BTREE;
ALTER TABLE d_ticket_user_1 ADD KEY `d_ticket_user_user_id_IDX` (`user_id`) USING BTREE;
