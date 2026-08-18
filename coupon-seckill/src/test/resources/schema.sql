-- ============================================================
-- 优惠券秒杀 建表脚本（MySQL 8+ / utf8mb4）
-- 对应 docs/01-技术设计.md §5
-- flash_sale_order / user_coupon 按 user_id % 2 分表（集成阶段切 ShardingSphere）
-- ============================================================

-- 券模板
CREATE TABLE IF NOT EXISTS `coupon_template` (
  `id`            BIGINT        NOT NULL COMMENT '主键(雪花)',
  `template_no`   VARCHAR(64)   NOT NULL COMMENT '模板业务编号',
  `name`          VARCHAR(128)  NOT NULL COMMENT '券名称',
  `type`          TINYINT       NOT NULL COMMENT '类型: 1满减 2折扣',
  `amount`        DECIMAL(10,2) NOT NULL COMMENT '面额(满减)/折扣率(折扣)',
  `min_amount`    DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '使用门槛(订单满X可用)',
  `valid_type`    TINYINT       NOT NULL COMMENT '有效期类型: 1固定时段 2领取后N天',
  `valid_start`   DATETIME      NULL COMMENT '固定时段-生效时间',
  `valid_end`     DATETIME      NULL COMMENT '固定时段-失效时间',
  `valid_days`    INT           NULL COMMENT '领取后N天有效',
  `scope`         TINYINT       NOT NULL DEFAULT 0 COMMENT '适用范围(0全场 1指定场次, 扩展)',
  `status`        TINYINT       NOT NULL DEFAULT 1 COMMENT '状态: 1启用 0停用',
  `create_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_template_no` (`template_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='优惠券模板';

-- 秒杀活动(场次): 库存内嵌
CREATE TABLE IF NOT EXISTS `flash_sale_activity` (
  `id`                 BIGINT       NOT NULL COMMENT '主键(雪花)',
  `activity_no`        VARCHAR(64)  NOT NULL COMMENT '活动业务编号',
  `coupon_template_id` BIGINT       NOT NULL COMMENT '绑定的券模板ID',
  `activity_name`      VARCHAR(128) NOT NULL COMMENT '活动名称',
  `start_time`         DATETIME     NOT NULL COMMENT '开始时间',
  `end_time`           DATETIME     NOT NULL COMMENT '结束时间',
  `total_stock`        INT          NOT NULL COMMENT '总库存',
  `stock`              INT          NOT NULL COMMENT '剩余库存(DB权威值, 乐观锁更新)',
  `per_user_limit`     INT          NOT NULL DEFAULT 1 COMMENT '每人限购',
  `status`             TINYINT      NOT NULL DEFAULT 0 COMMENT '0草稿 1未开始 2进行中 3已结束 4已下架',
  `version`            INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `create_time`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_activity_no` (`activity_no`),
  KEY `idx_status_time` (`status`, `start_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀活动';

-- 抢购流水: 分表 flash_sale_order_0 / flash_sale_order_1
CREATE TABLE IF NOT EXISTS `flash_sale_order_0` (
  `id`          BIGINT      NOT NULL COMMENT '主键(雪花)',
  `order_no`    VARCHAR(64) NOT NULL COMMENT '抢购流水号(业务幂等)',
  `activity_id` BIGINT      NOT NULL,
  `user_id`     BIGINT      NOT NULL,
  `request_id`  VARCHAR(64) NOT NULL COMMENT '客户端幂等键',
  `coupon_id`   BIGINT      NULL COMMENT '发券成功后回填 user_coupon.id',
  `status`      TINYINT     NOT NULL DEFAULT 0 COMMENT '0处理中 1已发券 2发券失败 3已回补',
  `retry_count` INT         NOT NULL DEFAULT 0 COMMENT '消费重试次数',
  `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  UNIQUE KEY `uk_activity_user_request` (`activity_id`, `user_id`, `request_id`),
  KEY `idx_user_time` (`user_id`, `create_time`),
  KEY `idx_activity_status` (`activity_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='抢购流水0';

CREATE TABLE IF NOT EXISTS `flash_sale_order_1` (
  `id`          BIGINT      NOT NULL COMMENT '主键(雪花)',
  `order_no`    VARCHAR(64) NOT NULL COMMENT '抢购流水号(业务幂等)',
  `activity_id` BIGINT      NOT NULL,
  `user_id`     BIGINT      NOT NULL,
  `request_id`  VARCHAR(64) NOT NULL COMMENT '客户端幂等键',
  `coupon_id`   BIGINT      NULL COMMENT '发券成功后回填 user_coupon.id',
  `status`      TINYINT     NOT NULL DEFAULT 0 COMMENT '0处理中 1已发券 2发券失败 3已回补',
  `retry_count` INT         NOT NULL DEFAULT 0 COMMENT '消费重试次数',
  `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  UNIQUE KEY `uk_activity_user_request` (`activity_id`, `user_id`, `request_id`),
  KEY `idx_user_time` (`user_id`, `create_time`),
  KEY `idx_activity_status` (`activity_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='抢购流水1';

-- 用户券: 分表 user_coupon_0 / user_coupon_1
CREATE TABLE IF NOT EXISTS `user_coupon_0` (
  `id`          BIGINT        NOT NULL COMMENT '主键(雪花)',
  `coupon_no`   VARCHAR(64)   NOT NULL COMMENT '券号(全局唯一)',
  `user_id`     BIGINT        NOT NULL,
  `activity_id` BIGINT        NULL COMMENT '来源秒杀活动(非秒杀发放为空)',
  `template_id` BIGINT        NOT NULL,
  `amount`      DECIMAL(10,2) NOT NULL COMMENT '面额快照',
  `min_amount`  DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '门槛快照',
  `valid_start` DATETIME      NOT NULL,
  `valid_end`   DATETIME      NOT NULL,
  `status`      TINYINT       NOT NULL DEFAULT 0 COMMENT '0未使用 1已锁定 2已使用 3已过期 4已作废',
  `order_no`    VARCHAR(64)   NULL COMMENT '关联订单(锁定/核销来源)',
  `lock_time`   DATETIME      NULL,
  `use_time`    DATETIME      NULL,
  `create_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_coupon_no` (`coupon_no`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_valid_end` (`valid_end`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户优惠券0';

CREATE TABLE IF NOT EXISTS `user_coupon_1` (
  `id`          BIGINT        NOT NULL COMMENT '主键(雪花)',
  `coupon_no`   VARCHAR(64)   NOT NULL COMMENT '券号(全局唯一)',
  `user_id`     BIGINT        NOT NULL,
  `activity_id` BIGINT        NULL COMMENT '来源秒杀活动(非秒杀发放为空)',
  `template_id` BIGINT        NOT NULL,
  `amount`      DECIMAL(10,2) NOT NULL COMMENT '面额快照',
  `min_amount`  DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '门槛快照',
  `valid_start` DATETIME      NOT NULL,
  `valid_end`   DATETIME      NOT NULL,
  `status`      TINYINT       NOT NULL DEFAULT 0 COMMENT '0未使用 1已锁定 2已使用 3已过期 4已作废',
  `order_no`    VARCHAR(64)   NULL COMMENT '关联订单(锁定/核销来源)',
  `lock_time`   DATETIME      NULL,
  `use_time`    DATETIME      NULL,
  `create_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_coupon_no` (`coupon_no`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_valid_end` (`valid_end`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户优惠券1';
