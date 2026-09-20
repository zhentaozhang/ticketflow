-- =============================================================================
-- 迁移：支付对账列 + 索引（pay_reconciliation_status）
--
-- 什么时候需要跑：
--   * 全新环境：直接跑 sql/cloud/ticketflow_order_{0,1}.sql 就行（里面已含本列与索引），不用跑这个；
--   * 已有环境：必须手工执行本脚本，否则新构建的 order-service 一启动，
--     所有订单查询都会报 Unknown column 'pay_reconciliation_status'（实体里加了字段，MP 会把它带进 SELECT）。
--
-- 为什么要单独一列：
--   d_order.reconciliation_status 已经被"Redis 流水 ↔ DB 订单"的库存对账占用（1→2），
--   两个流程共用一个字段会互相把对方标记成"已完成"。本列专门记"渠道账单 ↔ DB 订单"的支付对账状态。
--
-- 幂等性：不幂等。重复执行会报 Duplicate column；跑之前用下面的语句确认一下。
--   确认：show columns from ticketflow_order_0.d_order_0 like 'pay_reconciliation%';
-- =============================================================================

-- ---- ticketflow_order_0 ----
ALTER TABLE ticketflow_order_0.d_order_0
  ADD COLUMN pay_reconciliation_status int(3) DEFAULT '1' COMMENT '支付对账状态 1:未对账 2:对账完成(无需处理或已处理)',
  ADD KEY d_order_pay_reconcile_IDX (order_status, pay_reconciliation_status, cancel_order_time) USING BTREE;
ALTER TABLE ticketflow_order_0.d_order_1
  ADD COLUMN pay_reconciliation_status int(3) DEFAULT '1' COMMENT '支付对账状态 1:未对账 2:对账完成(无需处理或已处理)',
  ADD KEY d_order_pay_reconcile_IDX (order_status, pay_reconciliation_status, cancel_order_time) USING BTREE;
ALTER TABLE ticketflow_order_0.d_order_2
  ADD COLUMN pay_reconciliation_status int(3) DEFAULT '1' COMMENT '支付对账状态 1:未对账 2:对账完成(无需处理或已处理)',
  ADD KEY d_order_pay_reconcile_IDX (order_status, pay_reconciliation_status, cancel_order_time) USING BTREE;
ALTER TABLE ticketflow_order_0.d_order_3
  ADD COLUMN pay_reconciliation_status int(3) DEFAULT '1' COMMENT '支付对账状态 1:未对账 2:对账完成(无需处理或已处理)',
  ADD KEY d_order_pay_reconcile_IDX (order_status, pay_reconciliation_status, cancel_order_time) USING BTREE;
ALTER TABLE ticketflow_order_0.d_order_4
  ADD COLUMN pay_reconciliation_status int(3) DEFAULT '1' COMMENT '支付对账状态 1:未对账 2:对账完成(无需处理或已处理)',
  ADD KEY d_order_pay_reconcile_IDX (order_status, pay_reconciliation_status, cancel_order_time) USING BTREE;
ALTER TABLE ticketflow_order_0.d_order_5
  ADD COLUMN pay_reconciliation_status int(3) DEFAULT '1' COMMENT '支付对账状态 1:未对账 2:对账完成(无需处理或已处理)',
  ADD KEY d_order_pay_reconcile_IDX (order_status, pay_reconciliation_status, cancel_order_time) USING BTREE;
ALTER TABLE ticketflow_order_0.d_order_6
  ADD COLUMN pay_reconciliation_status int(3) DEFAULT '1' COMMENT '支付对账状态 1:未对账 2:对账完成(无需处理或已处理)',
  ADD KEY d_order_pay_reconcile_IDX (order_status, pay_reconciliation_status, cancel_order_time) USING BTREE;
ALTER TABLE ticketflow_order_0.d_order_7
  ADD COLUMN pay_reconciliation_status int(3) DEFAULT '1' COMMENT '支付对账状态 1:未对账 2:对账完成(无需处理或已处理)',
  ADD KEY d_order_pay_reconcile_IDX (order_status, pay_reconciliation_status, cancel_order_time) USING BTREE;

-- ---- ticketflow_order_1 ----
ALTER TABLE ticketflow_order_1.d_order_0
  ADD COLUMN pay_reconciliation_status int(3) DEFAULT '1' COMMENT '支付对账状态 1:未对账 2:对账完成(无需处理或已处理)',
  ADD KEY d_order_pay_reconcile_IDX (order_status, pay_reconciliation_status, cancel_order_time) USING BTREE;
ALTER TABLE ticketflow_order_1.d_order_1
  ADD COLUMN pay_reconciliation_status int(3) DEFAULT '1' COMMENT '支付对账状态 1:未对账 2:对账完成(无需处理或已处理)',
  ADD KEY d_order_pay_reconcile_IDX (order_status, pay_reconciliation_status, cancel_order_time) USING BTREE;
ALTER TABLE ticketflow_order_1.d_order_2
  ADD COLUMN pay_reconciliation_status int(3) DEFAULT '1' COMMENT '支付对账状态 1:未对账 2:对账完成(无需处理或已处理)',
  ADD KEY d_order_pay_reconcile_IDX (order_status, pay_reconciliation_status, cancel_order_time) USING BTREE;
ALTER TABLE ticketflow_order_1.d_order_3
  ADD COLUMN pay_reconciliation_status int(3) DEFAULT '1' COMMENT '支付对账状态 1:未对账 2:对账完成(无需处理或已处理)',
  ADD KEY d_order_pay_reconcile_IDX (order_status, pay_reconciliation_status, cancel_order_time) USING BTREE;
ALTER TABLE ticketflow_order_1.d_order_4
  ADD COLUMN pay_reconciliation_status int(3) DEFAULT '1' COMMENT '支付对账状态 1:未对账 2:对账完成(无需处理或已处理)',
  ADD KEY d_order_pay_reconcile_IDX (order_status, pay_reconciliation_status, cancel_order_time) USING BTREE;
ALTER TABLE ticketflow_order_1.d_order_5
  ADD COLUMN pay_reconciliation_status int(3) DEFAULT '1' COMMENT '支付对账状态 1:未对账 2:对账完成(无需处理或已处理)',
  ADD KEY d_order_pay_reconcile_IDX (order_status, pay_reconciliation_status, cancel_order_time) USING BTREE;
ALTER TABLE ticketflow_order_1.d_order_6
  ADD COLUMN pay_reconciliation_status int(3) DEFAULT '1' COMMENT '支付对账状态 1:未对账 2:对账完成(无需处理或已处理)',
  ADD KEY d_order_pay_reconcile_IDX (order_status, pay_reconciliation_status, cancel_order_time) USING BTREE;
ALTER TABLE ticketflow_order_1.d_order_7
  ADD COLUMN pay_reconciliation_status int(3) DEFAULT '1' COMMENT '支付对账状态 1:未对账 2:对账完成(无需处理或已处理)',
  ADD KEY d_order_pay_reconcile_IDX (order_status, pay_reconciliation_status, cancel_order_time) USING BTREE;

