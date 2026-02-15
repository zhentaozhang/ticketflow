-- =============================================
-- 压测专用测试数据
-- 在 docker MySQL 中通过 docker exec 执行（绕过 ShardingSphere）
-- 目标：创建一个专用压测节目 + 票档 + 100个用户
-- =============================================

-- ========== 节目数据 ==========
-- program_id=9999 → id%2=1 → ds_1 (ticketflow_program_1)
--                   (id/2)%2 = (4999)%2 = 1 → d_program_1
-- ticket_category 按 program_id 路由：program_id%2=1 → ds_1, (program_id/2)%2=1 → d_ticket_category_1

-- 1. 节目分组（group_id=5000 → 5000%2=0 → ds_0, (5000/2)%2=0 → d_program_group_0）
INSERT IGNORE INTO `ticketflow_program_0`.`d_program_group_0` (`id`, `program_json`, `recent_show_time`, `create_time`, `edit_time`, `status`)
VALUES (5000, '[{"programId":9999,"areaId":2,"areaIdName":"北京"}]', '2026-06-30 19:30:00', NOW(), NOW(), 1);

-- 2. 节目主表
INSERT IGNORE INTO `ticketflow_program_1`.`d_program_1` (
  `id`, `program_group_id`, `prime`, `area_id`, `program_category_id`, `parent_program_category_id`,
  `title`, `actor`, `place`, `item_picture`, `pre_sell`, `detail`,
  `per_order_limit_purchase_count`, `per_account_limit_purchase_count`,
  `refund_ticket_rule`, `delivery_instruction`, `entry_rule`, `child_purchase`,
  `invoice_specification`, `real_ticket_purchase_rule`, `abnormal_order_description`, `kind_reminder`,
  `performance_duration`, `entry_time`, `rel_name_ticket_entrance`, `permit_choose_seat`,
  `electronic_delivery_ticket`, `electronic_invoice`, `high_heat`, `program_status`,
  `issue_time`, `create_time`, `edit_time`, `status`
) VALUES (
  9999, 5000, 1, 2, 1, 1,
  '【压测专用】群星演唱会-2026北京站', '群星', '国家体育场（鸟巢）',
  NULL, 0, '本次演出的最终解释权归主办方所有。',
  6, 6,
  '不支持退换。', '不支持修改配送地址', '须打开【票夹】扫码入场，截图无效。',
  '儿童一律凭票入场', '演出开始前提交发票申请。',
  '一个订单对应一个证件', '异常订单说明', '温馨提示',
  '约120分钟', '提前60分钟', 0, 0,
  1, 1, 0, 1,
  NOW(), NOW(), NOW(), 1
);

-- 3. 节目场次
INSERT IGNORE INTO `ticketflow_program_1`.`d_program_show_time_1` (
  `id`, `program_id`, `show_time`, `show_day_time`, `show_week_time`, `area_id`, `create_time`, `edit_time`, `status`
) VALUES (
  9991, 9999, '2026-06-30 19:30:00', '2026-06-30 19:30:00', '周六', 2, NOW(), NOW(), 1
);

-- 4. 票档（7个票档，总库存 120000）
INSERT IGNORE INTO `ticketflow_program_1`.`d_ticket_category_1` (`id`, `program_id`, `introduce`, `price`, `total_number`, `remain_number`, `create_time`, `edit_time`, `status`)
VALUES
  (901, 9999, '看台 199元', 199, 20000, 20000, NOW(), NOW(), 1),
  (902, 9999, '看台 399元', 399, 20000, 20000, NOW(), NOW(), 1),
  (903, 9999, '看台 599元', 599, 20000, 20000, NOW(), NOW(), 1),
  (904, 9999, '内场 899元', 899, 20000, 20000, NOW(), NOW(), 1),
  (905, 9999, '内场 1299元', 1299, 20000, 20000, NOW(), NOW(), 1),
  (906, 9999, 'VIP 1999元', 1999, 10000, 10000, NOW(), NOW(), 1),
  (907, 9999, '至尊VIP 2999元', 2999, 10000, 10000, NOW(), NOW(), 1);


-- ========== 用户数据 ==========
-- d_user 分片: id % 2 → ds, (id/2) % 2 → table
-- d_ticket_user 分片: user_id % 2 → ds, (user_id/2) % 2 → table
-- 用 MySQL 8.0 的递归 CTE 生成 100 个用户

-- 5. 插入用户（均匀分布到 4 个分片）
-- ticketflow_user_0.d_user_0  ← id%2=0, (id/2)%2=0  → id=4,8,12,...
-- ticketflow_user_0.d_user_1  ← id%2=0, (id/2)%2=1  → id=2,6,10,...
-- ticketflow_user_1.d_user_0  ← id%2=1, (id/2)%2=0  → id=1,5,9,...
-- ticketflow_user_1.d_user_1  ← id%2=1, (id/2)%2=1  → id=3,7,11,...

INSERT IGNORE INTO `ticketflow_user_0`.`d_user_0` (`id`, `name`, `mobile`, `password`, `create_time`, `edit_time`, `status`)
WITH RECURSIVE seq(n) AS (SELECT 4 UNION ALL SELECT n+4 FROM seq WHERE n < 100)
SELECT n, CONCAT('benchmark_user_', n), CONCAT('1380000', LPAD(n, 4, '0')),
       'e10adc3949ba59abbe56e057f20f883e', NOW(), NOW(), 1 FROM seq;

INSERT IGNORE INTO `ticketflow_user_0`.`d_user_1` (`id`, `name`, `mobile`, `password`, `create_time`, `edit_time`, `status`)
WITH RECURSIVE seq(n) AS (SELECT 2 UNION ALL SELECT n+4 FROM seq WHERE n < 100)
SELECT n, CONCAT('benchmark_user_', n), CONCAT('1380000', LPAD(n, 4, '0')),
       'e10adc3949ba59abbe56e057f20f883e', NOW(), NOW(), 1 FROM seq;

INSERT IGNORE INTO `ticketflow_user_1`.`d_user_0` (`id`, `name`, `mobile`, `password`, `create_time`, `edit_time`, `status`)
WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n+4 FROM seq WHERE n < 100)
SELECT n, CONCAT('benchmark_user_', n), CONCAT('1380000', LPAD(n, 4, '0')),
       'e10adc3949ba59abbe56e057f20f883e', NOW(), NOW(), 1 FROM seq;

INSERT IGNORE INTO `ticketflow_user_1`.`d_user_1` (`id`, `name`, `mobile`, `password`, `create_time`, `edit_time`, `status`)
WITH RECURSIVE seq(n) AS (SELECT 3 UNION ALL SELECT n+4 FROM seq WHERE n < 100)
SELECT n, CONCAT('benchmark_user_', n), CONCAT('1380000', LPAD(n, 4, '0')),
       'e10adc3949ba59abbe56e057f20f883e', NOW(), NOW(), 1 FROM seq;

-- 6. 插入购票人（每个用户自己作为购票人，user_id 路由规则同 d_user）
INSERT IGNORE INTO `ticketflow_user_0`.`d_ticket_user_0` (`id`, `user_id`, `rel_name`, `id_type`, `id_number`, `create_time`, `edit_time`, `status`)
SELECT id, id, name, 1, CONCAT('11010119900101', LPAD(id, 4, '0')), NOW(), NOW(), 1
FROM `ticketflow_user_0`.`d_user_0` WHERE name LIKE 'benchmark%';

INSERT IGNORE INTO `ticketflow_user_0`.`d_ticket_user_1` (`id`, `user_id`, `rel_name`, `id_type`, `id_number`, `create_time`, `edit_time`, `status`)
SELECT id, id, name, 1, CONCAT('11010119900101', LPAD(id, 4, '0')), NOW(), NOW(), 1
FROM `ticketflow_user_0`.`d_user_1` WHERE name LIKE 'benchmark%';

INSERT IGNORE INTO `ticketflow_user_1`.`d_ticket_user_0` (`id`, `user_id`, `rel_name`, `id_type`, `id_number`, `create_time`, `edit_time`, `status`)
SELECT id, id, name, 1, CONCAT('11010119900101', LPAD(id, 4, '0')), NOW(), NOW(), 1
FROM `ticketflow_user_1`.`d_user_0` WHERE name LIKE 'benchmark%';

INSERT IGNORE INTO `ticketflow_user_1`.`d_ticket_user_1` (`id`, `user_id`, `rel_name`, `id_type`, `id_number`, `create_time`, `edit_time`, `status`)
SELECT id, id, name, 1, CONCAT('11010119900101', LPAD(id, 4, '0')), NOW(), NOW(), 1
FROM `ticketflow_user_1`.`d_user_1` WHERE name LIKE 'benchmark%';
