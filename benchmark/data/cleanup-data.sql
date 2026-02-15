-- =============================================
-- 清理压测数据（压测结束后运行）
-- =============================================
DELETE FROM `ticketflow_program_1`.`d_program_1` WHERE id = 9999;
DELETE FROM `ticketflow_program_0`.`d_program_group_0` WHERE id = 5000;
DELETE FROM `ticketflow_program_1`.`d_program_show_time_1` WHERE program_id = 9999;
DELETE FROM `ticketflow_program_1`.`d_ticket_category_1` WHERE program_id = 9999;
DELETE FROM `ticketflow_user_0`.`d_user_0` WHERE name LIKE 'benchmark%';
DELETE FROM `ticketflow_user_1`.`d_user_1` WHERE name LIKE 'benchmark%';
DELETE FROM `ticketflow_user_0`.`d_ticket_user_0` WHERE name LIKE 'benchmark%';
DELETE FROM `ticketflow_user_1`.`d_ticket_user_1` WHERE name LIKE 'benchmark%';
