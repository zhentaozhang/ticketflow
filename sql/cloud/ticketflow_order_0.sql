USE ticketflow_order_0;
--
-- Table structure for table `d_order_0`
--

DROP TABLE IF EXISTS `d_order_0`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `d_order_0` (
  `id` bigint(20) NOT NULL COMMENT '主键id',
  `order_number` bigint(20) NOT NULL COMMENT '订单编号',
  `identifier_id` bigint(20) DEFAULT NULL COMMENT '记录id',
  `program_id` bigint(20) NOT NULL COMMENT '节目表id',
  `program_item_picture` varchar(1024) DEFAULT NULL COMMENT '节目图片介绍',
  `user_id` bigint(20) NOT NULL COMMENT '用户id',
  `program_title` varchar(512) DEFAULT NULL COMMENT '节目标题',
  `program_place` varchar(100) DEFAULT NULL COMMENT '节目地点',
  `program_show_time` datetime DEFAULT NULL COMMENT '节目演出时间',
  `program_permit_choose_seat` tinyint(1) NOT NULL COMMENT '节目是否允许选座 1:允许选座 0:不允许选座',
  `distribution_mode` varchar(256) DEFAULT NULL COMMENT '配送方式',
  `take_ticket_mode` varchar(256) DEFAULT NULL COMMENT '取票方式',
  `order_price` decimal(10,0) DEFAULT NULL COMMENT '订单价格',
  `pay_order_type` int(3) DEFAULT NULL COMMENT '支付订单方式',
  `order_status` int(3) DEFAULT '1' COMMENT '订单状态 1:未支付 2:已取消 3:已支付 4:已退单',
  `reconciliation_status` int(3) DEFAULT '1' COMMENT '对账状态 1:未对账 -1:对账完成有问题 2:对账完成没有问题 3:对账有问题处理完毕',
  `order_version` int(3) NOT NULL DEFAULT '1' COMMENT '创建订单的版本',
  `create_order_time` datetime DEFAULT NULL COMMENT '生成订单时间',
  `cancel_order_time` datetime DEFAULT NULL COMMENT '取消订单时间',
  `pay_order_time` datetime DEFAULT NULL COMMENT '支付订单时间',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `d_order_order_number_IDX` (`order_number`) USING BTREE,
  KEY `user_id_IDX` (`user_id`) USING BTREE,
  KEY `program_id_IDX` (`program_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='订单表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `d_order_0`
--

LOCK TABLES `d_order_0` WRITE;
/*!40000 ALTER TABLE `d_order_0` DISABLE KEYS */;
/*!40000 ALTER TABLE `d_order_0` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `d_order_1`
--

DROP TABLE IF EXISTS `d_order_1`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `d_order_1` (
  `id` bigint(20) NOT NULL COMMENT '主键id',
  `order_number` bigint(20) NOT NULL COMMENT '订单编号',
  `identifier_id` bigint(20) DEFAULT NULL COMMENT '记录id',
  `program_id` bigint(20) NOT NULL COMMENT '节目表id',
  `program_item_picture` varchar(1024) DEFAULT NULL COMMENT '节目图片介绍',
  `user_id` bigint(20) NOT NULL COMMENT '用户id',
  `program_title` varchar(512) DEFAULT NULL COMMENT '节目标题',
  `program_place` varchar(100) DEFAULT NULL COMMENT '节目地点',
  `program_show_time` datetime DEFAULT NULL COMMENT '节目演出时间',
  `program_permit_choose_seat` tinyint(1) NOT NULL COMMENT '节目是否允许选座 1:允许选座 0:不允许选座',
  `distribution_mode` varchar(256) DEFAULT NULL COMMENT '配送方式',
  `take_ticket_mode` varchar(256) DEFAULT NULL COMMENT '取票方式',
  `order_price` decimal(10,0) DEFAULT NULL COMMENT '订单价格',
  `pay_order_type` int(3) DEFAULT NULL COMMENT '支付订单方式',
  `order_status` int(3) DEFAULT '1' COMMENT '订单状态 1:未支付 2:已取消 3:已支付 4:已退单',
  `reconciliation_status` int(3) DEFAULT '1' COMMENT '对账状态 1:未对账 -1:对账完成有问题 2:对账完成没有问题 3:对账有问题处理完毕',
  `order_version` int(3) NOT NULL DEFAULT '1' COMMENT '创建订单的版本',
  `create_order_time` datetime DEFAULT NULL COMMENT '生成订单时间',
  `cancel_order_time` datetime DEFAULT NULL COMMENT '取消订单时间',
  `pay_order_time` datetime DEFAULT NULL COMMENT '支付订单时间',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `d_order_order_number_IDX` (`order_number`) USING BTREE,
  KEY `user_id_IDX` (`user_id`) USING BTREE,
  KEY `program_id_IDX` (`program_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='订单表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `d_order_1`
--

LOCK TABLES `d_order_1` WRITE;
/*!40000 ALTER TABLE `d_order_1` DISABLE KEYS */;
/*!40000 ALTER TABLE `d_order_1` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `d_order_2`
--

DROP TABLE IF EXISTS `d_order_2`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `d_order_2` (
  `id` bigint(20) NOT NULL COMMENT '主键id',
  `order_number` bigint(20) NOT NULL COMMENT '订单编号',
  `identifier_id` bigint(20) DEFAULT NULL COMMENT '记录id',
  `program_id` bigint(20) NOT NULL COMMENT '节目表id',
  `program_item_picture` varchar(1024) DEFAULT NULL COMMENT '节目图片介绍',
  `user_id` bigint(20) NOT NULL COMMENT '用户id',
  `program_title` varchar(512) DEFAULT NULL COMMENT '节目标题',
  `program_place` varchar(100) DEFAULT NULL COMMENT '节目地点',
  `program_show_time` datetime DEFAULT NULL COMMENT '节目演出时间',
  `program_permit_choose_seat` tinyint(1) NOT NULL COMMENT '节目是否允许选座 1:允许选座 0:不允许选座',
  `distribution_mode` varchar(256) DEFAULT NULL COMMENT '配送方式',
  `take_ticket_mode` varchar(256) DEFAULT NULL COMMENT '取票方式',
  `order_price` decimal(10,0) DEFAULT NULL COMMENT '订单价格',
  `pay_order_type` int(3) DEFAULT NULL COMMENT '支付订单方式',
  `order_status` int(3) DEFAULT '1' COMMENT '订单状态 1:未支付 2:已取消 3:已支付 4:已退单',
  `reconciliation_status` int(3) DEFAULT '1' COMMENT '对账状态 1:未对账 -1:对账完成有问题 2:对账完成没有问题 3:对账有问题处理完毕',
  `order_version` int(3) NOT NULL DEFAULT '1' COMMENT '创建订单的版本',
  `create_order_time` datetime DEFAULT NULL COMMENT '生成订单时间',
  `cancel_order_time` datetime DEFAULT NULL COMMENT '取消订单时间',
  `pay_order_time` datetime DEFAULT NULL COMMENT '支付订单时间',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `d_order_order_number_IDX` (`order_number`) USING BTREE,
  KEY `user_id_IDX` (`user_id`) USING BTREE,
  KEY `program_id_IDX` (`program_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='订单表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `d_order_2`
--

LOCK TABLES `d_order_2` WRITE;
/*!40000 ALTER TABLE `d_order_2` DISABLE KEYS */;
/*!40000 ALTER TABLE `d_order_2` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `d_order_3`
--

DROP TABLE IF EXISTS `d_order_3`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `d_order_3` (
  `id` bigint(20) NOT NULL COMMENT '主键id',
  `order_number` bigint(20) NOT NULL COMMENT '订单编号',
  `identifier_id` bigint(20) DEFAULT NULL COMMENT '记录id',
  `program_id` bigint(20) NOT NULL COMMENT '节目表id',
  `program_item_picture` varchar(1024) DEFAULT NULL COMMENT '节目图片介绍',
  `user_id` bigint(20) NOT NULL COMMENT '用户id',
  `program_title` varchar(512) DEFAULT NULL COMMENT '节目标题',
  `program_place` varchar(100) DEFAULT NULL COMMENT '节目地点',
  `program_show_time` datetime DEFAULT NULL COMMENT '节目演出时间',
  `program_permit_choose_seat` tinyint(1) NOT NULL COMMENT '节目是否允许选座 1:允许选座 0:不允许选座',
  `distribution_mode` varchar(256) DEFAULT NULL COMMENT '配送方式',
  `take_ticket_mode` varchar(256) DEFAULT NULL COMMENT '取票方式',
  `order_price` decimal(10,0) DEFAULT NULL COMMENT '订单价格',
  `pay_order_type` int(3) DEFAULT NULL COMMENT '支付订单方式',
  `order_status` int(3) DEFAULT '1' COMMENT '订单状态 1:未支付 2:已取消 3:已支付 4:已退单',
  `reconciliation_status` int(3) DEFAULT '1' COMMENT '对账状态 1:未对账 -1:对账完成有问题 2:对账完成没有问题 3:对账有问题处理完毕',
  `order_version` int(3) NOT NULL DEFAULT '1' COMMENT '创建订单的版本',
  `create_order_time` datetime DEFAULT NULL COMMENT '生成订单时间',
  `cancel_order_time` datetime DEFAULT NULL COMMENT '取消订单时间',
  `pay_order_time` datetime DEFAULT NULL COMMENT '支付订单时间',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `d_order_order_number_IDX` (`order_number`) USING BTREE,
  KEY `user_id_IDX` (`user_id`) USING BTREE,
  KEY `program_id_IDX` (`program_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='订单表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `d_order_3`
--

LOCK TABLES `d_order_3` WRITE;
/*!40000 ALTER TABLE `d_order_3` DISABLE KEYS */;
/*!40000 ALTER TABLE `d_order_3` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `d_order_ticket_user_0`
--

DROP TABLE IF EXISTS `d_order_ticket_user_0`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `d_order_ticket_user_0` (
  `id` bigint(20) NOT NULL COMMENT '主键id',
  `order_number` bigint(20) NOT NULL COMMENT '订单编号',
  `program_id` bigint(20) NOT NULL COMMENT '节目表id',
  `user_id` bigint(20) NOT NULL COMMENT '用户id',
  `ticket_user_id` bigint(20) NOT NULL COMMENT '购票人id',
  `seat_id` bigint(20) NOT NULL COMMENT '座位id',
  `seat_info` varchar(100) DEFAULT NULL COMMENT '座位信息',
  `ticket_category_id` bigint(20) DEFAULT NULL COMMENT '节目票档id',
  `order_price` decimal(10,0) DEFAULT NULL COMMENT '订单价格',
  `pay_order_price` decimal(10,0) DEFAULT NULL COMMENT '支付订单价格',
  `pay_order_type` int(3) DEFAULT NULL COMMENT '支付订单方式',
  `order_status` int(3) DEFAULT '1' COMMENT '订单状态 1:未支付 2:已取消 3:已支付 4:已退单',
  `reconciliation_status` int(3) DEFAULT '1' COMMENT '对账状态 1:未对账 -1:对账完成有问题 2:对账完成没有问题 3:对账有问题处理完毕',
  `create_order_time` datetime DEFAULT NULL COMMENT '生成订单时间',
  `cancel_order_time` datetime DEFAULT NULL COMMENT '取消订单时间',
  `pay_order_time` datetime DEFAULT NULL COMMENT '支付订单时间',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  KEY `d_order_ticket_user_order_id_IDX` (`order_number`) USING BTREE,
  KEY `d_order_ticket_user_user_id_IDX` (`user_id`) USING BTREE,
  KEY `d_order_ticket_user_ticket_user_id_IDX` (`ticket_user_id`) USING BTREE,
  KEY `d_order_ticket_user_create_order_time_IDX` (`create_order_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='购票人订单表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `d_order_ticket_user_0`
--

LOCK TABLES `d_order_ticket_user_0` WRITE;
/*!40000 ALTER TABLE `d_order_ticket_user_0` DISABLE KEYS */;
/*!40000 ALTER TABLE `d_order_ticket_user_0` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `d_order_ticket_user_1`
--

DROP TABLE IF EXISTS `d_order_ticket_user_1`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `d_order_ticket_user_1` (
  `id` bigint(20) NOT NULL COMMENT '主键id',
  `order_number` bigint(20) NOT NULL COMMENT '订单编号',
  `program_id` bigint(20) NOT NULL COMMENT '节目表id',
  `user_id` bigint(20) NOT NULL COMMENT '用户id',
  `ticket_user_id` bigint(20) NOT NULL COMMENT '购票人id',
  `seat_id` bigint(20) NOT NULL COMMENT '座位id',
  `seat_info` varchar(100) DEFAULT NULL COMMENT '座位信息',
  `ticket_category_id` bigint(20) DEFAULT NULL COMMENT '节目票档id',
  `order_price` decimal(10,0) DEFAULT NULL COMMENT '订单价格',
  `pay_order_price` decimal(10,0) DEFAULT NULL COMMENT '支付订单价格',
  `pay_order_type` int(3) DEFAULT NULL COMMENT '支付订单方式',
  `order_status` int(3) DEFAULT '1' COMMENT '订单状态 1:未支付 2:已取消 3:已支付 4:已退单',
  `reconciliation_status` int(3) DEFAULT '1' COMMENT '对账状态 1:未对账 -1:对账完成有问题 2:对账完成没有问题 3:对账有问题处理完毕',
  `create_order_time` datetime DEFAULT NULL COMMENT '生成订单时间',
  `cancel_order_time` datetime DEFAULT NULL COMMENT '取消订单时间',
  `pay_order_time` datetime DEFAULT NULL COMMENT '支付订单时间',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  KEY `d_order_ticket_user_order_id_IDX` (`order_number`) USING BTREE,
  KEY `d_order_ticket_user_user_id_IDX` (`user_id`) USING BTREE,
  KEY `d_order_ticket_user_ticket_user_id_IDX` (`ticket_user_id`) USING BTREE,
  KEY `d_order_ticket_user_create_order_time_IDX` (`create_order_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='购票人订单表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `d_order_ticket_user_1`
--

LOCK TABLES `d_order_ticket_user_1` WRITE;
/*!40000 ALTER TABLE `d_order_ticket_user_1` DISABLE KEYS */;
/*!40000 ALTER TABLE `d_order_ticket_user_1` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `d_order_ticket_user_2`
--

DROP TABLE IF EXISTS `d_order_ticket_user_2`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `d_order_ticket_user_2` (
  `id` bigint(20) NOT NULL COMMENT '主键id',
  `order_number` bigint(20) NOT NULL COMMENT '订单编号',
  `program_id` bigint(20) NOT NULL COMMENT '节目表id',
  `user_id` bigint(20) NOT NULL COMMENT '用户id',
  `ticket_user_id` bigint(20) NOT NULL COMMENT '购票人id',
  `seat_id` bigint(20) NOT NULL COMMENT '座位id',
  `seat_info` varchar(100) DEFAULT NULL COMMENT '座位信息',
  `ticket_category_id` bigint(20) DEFAULT NULL COMMENT '节目票档id',
  `order_price` decimal(10,0) DEFAULT NULL COMMENT '订单价格',
  `pay_order_price` decimal(10,0) DEFAULT NULL COMMENT '支付订单价格',
  `pay_order_type` int(3) DEFAULT NULL COMMENT '支付订单方式',
  `order_status` int(3) DEFAULT '1' COMMENT '订单状态 1:未支付 2:已取消 3:已支付 4:已退单',
  `reconciliation_status` int(3) DEFAULT '1' COMMENT '对账状态 1:未对账 -1:对账完成有问题 2:对账完成没有问题 3:对账有问题处理完毕',
  `create_order_time` datetime DEFAULT NULL COMMENT '生成订单时间',
  `cancel_order_time` datetime DEFAULT NULL COMMENT '取消订单时间',
  `pay_order_time` datetime DEFAULT NULL COMMENT '支付订单时间',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  KEY `d_order_ticket_user_order_id_IDX` (`order_number`) USING BTREE,
  KEY `d_order_ticket_user_user_id_IDX` (`user_id`) USING BTREE,
  KEY `d_order_ticket_user_ticket_user_id_IDX` (`ticket_user_id`) USING BTREE,
  KEY `d_order_ticket_user_create_order_time_IDX` (`create_order_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='购票人订单表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `d_order_ticket_user_2`
--

LOCK TABLES `d_order_ticket_user_2` WRITE;
/*!40000 ALTER TABLE `d_order_ticket_user_2` DISABLE KEYS */;
/*!40000 ALTER TABLE `d_order_ticket_user_2` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `d_order_ticket_user_3`
--

DROP TABLE IF EXISTS `d_order_ticket_user_3`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `d_order_ticket_user_3` (
  `id` bigint(20) NOT NULL COMMENT '主键id',
  `order_number` bigint(20) NOT NULL COMMENT '订单编号',
  `program_id` bigint(20) NOT NULL COMMENT '节目表id',
  `user_id` bigint(20) NOT NULL COMMENT '用户id',
  `ticket_user_id` bigint(20) NOT NULL COMMENT '购票人id',
  `seat_id` bigint(20) NOT NULL COMMENT '座位id',
  `seat_info` varchar(100) DEFAULT NULL COMMENT '座位信息',
  `ticket_category_id` bigint(20) DEFAULT NULL COMMENT '节目票档id',
  `order_price` decimal(10,0) DEFAULT NULL COMMENT '订单价格',
  `pay_order_price` decimal(10,0) DEFAULT NULL COMMENT '支付订单价格',
  `pay_order_type` int(3) DEFAULT NULL COMMENT '支付订单方式',
  `order_status` int(3) DEFAULT '1' COMMENT '订单状态 1:未支付 2:已取消 3:已支付 4:已退单',
  `reconciliation_status` int(3) DEFAULT '1' COMMENT '对账状态 1:未对账 -1:对账完成有问题 2:对账完成没有问题 3:对账有问题处理完毕',
  `create_order_time` datetime DEFAULT NULL COMMENT '生成订单时间',
  `cancel_order_time` datetime DEFAULT NULL COMMENT '取消订单时间',
  `pay_order_time` datetime DEFAULT NULL COMMENT '支付订单时间',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  KEY `d_order_ticket_user_order_id_IDX` (`order_number`) USING BTREE,
  KEY `d_order_ticket_user_user_id_IDX` (`user_id`) USING BTREE,
  KEY `d_order_ticket_user_ticket_user_id_IDX` (`ticket_user_id`) USING BTREE,
  KEY `d_order_ticket_user_create_order_time_IDX` (`create_order_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='购票人订单表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `d_order_ticket_user_3`
--

LOCK TABLES `d_order_ticket_user_3` WRITE;
/*!40000 ALTER TABLE `d_order_ticket_user_3` DISABLE KEYS */;
/*!40000 ALTER TABLE `d_order_ticket_user_3` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `d_order_ticket_user_record_0`
--

DROP TABLE IF EXISTS `d_order_ticket_user_record_0`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `d_order_ticket_user_record_0` (
  `id` bigint(20) NOT NULL COMMENT '主键id',
  `order_number` bigint(20) NOT NULL COMMENT '订单编号',
  `identifier_id` bigint(20) DEFAULT NULL COMMENT '记录id',
  `ticket_user_order_id` bigint(20) NOT NULL COMMENT '购票人订单id',
  `program_id` bigint(20) NOT NULL COMMENT '节目表id',
  `user_id` bigint(20) NOT NULL COMMENT '用户id',
  `ticket_user_id` bigint(20) NOT NULL COMMENT '购票人id',
  `seat_id` bigint(20) NOT NULL COMMENT '座位id',
  `seat_info` varchar(100) DEFAULT NULL COMMENT '座位信息',
  `ticket_category_id` bigint(20) DEFAULT NULL COMMENT '节目票档id',
  `order_price` decimal(10,0) DEFAULT NULL COMMENT '订单价格',
  `record_type_code` int(3) DEFAULT NULL COMMENT '记录类型编码 -1:扣减余票 0:改变状态 1:增加余票',
  `record_type_value` varchar(256) DEFAULT NULL COMMENT '记录类型值 -1:扣减余票(reduce) 0:改变状态(changeStatus) 1:增加余票(increase)',
  `reconciliation_status` int(3) DEFAULT '1' COMMENT '对账状态 1:未对账 -1:对账完成有问题 2:对账完成没有问题 3:对账有问题处理完毕',
  `create_type` int(3) NOT NULL DEFAULT '1' COMMENT '创建类型 1:正常创建 2:补偿创建',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  KEY `d_order_ticket_user_record_order_id_IDX` (`order_number`) USING BTREE,
  KEY `d_order_ticket_user_record_ticket_user_order_id_IDX` (`ticket_user_order_id`) USING BTREE,
  KEY `d_order_ticket_user_record_user_id_IDX` (`user_id`) USING BTREE,
  KEY `d_order_ticket_user_record_ticket_user_id_IDX` (`ticket_user_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='购票人订单记录表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `d_order_ticket_user_record_0`
--

LOCK TABLES `d_order_ticket_user_record_0` WRITE;
/*!40000 ALTER TABLE `d_order_ticket_user_record_0` DISABLE KEYS */;
/*!40000 ALTER TABLE `d_order_ticket_user_record_0` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `d_order_ticket_user_record_1`
--

DROP TABLE IF EXISTS `d_order_ticket_user_record_1`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `d_order_ticket_user_record_1` (
  `id` bigint(20) NOT NULL COMMENT '主键id',
  `order_number` bigint(20) NOT NULL COMMENT '订单编号',
  `identifier_id` bigint(20) DEFAULT NULL COMMENT '记录id',
  `ticket_user_order_id` bigint(20) NOT NULL COMMENT '购票人订单id',
  `program_id` bigint(20) NOT NULL COMMENT '节目表id',
  `user_id` bigint(20) NOT NULL COMMENT '用户id',
  `ticket_user_id` bigint(20) NOT NULL COMMENT '购票人id',
  `seat_id` bigint(20) NOT NULL COMMENT '座位id',
  `seat_info` varchar(100) DEFAULT NULL COMMENT '座位信息',
  `ticket_category_id` bigint(20) DEFAULT NULL COMMENT '节目票档id',
  `order_price` decimal(10,0) DEFAULT NULL COMMENT '订单价格',
  `record_type_code` int(3) DEFAULT NULL COMMENT '记录类型编码 -1:扣减余票 0:改变状态 1:增加余票',
  `record_type_value` varchar(256) DEFAULT NULL COMMENT '记录类型值 -1:扣减余票(reduce) 0:改变状态(changeStatus) 1:增加余票(increase)',
  `reconciliation_status` int(3) DEFAULT '1' COMMENT '对账状态 1:未对账 -1:对账完成有问题 2:对账完成没有问题 3:对账有问题处理完毕',
  `create_type` int(3) NOT NULL DEFAULT '1' COMMENT '创建类型 1:正常创建 2:补偿创建',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  KEY `d_order_ticket_user_record_order_id_IDX` (`order_number`) USING BTREE,
  KEY `d_order_ticket_user_record_ticket_user_order_id_IDX` (`ticket_user_order_id`) USING BTREE,
  KEY `d_order_ticket_user_record_user_id_IDX` (`user_id`) USING BTREE,
  KEY `d_order_ticket_user_record_ticket_user_id_IDX` (`ticket_user_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='购票人订单记录表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `d_order_ticket_user_record_1`
--

LOCK TABLES `d_order_ticket_user_record_1` WRITE;
/*!40000 ALTER TABLE `d_order_ticket_user_record_1` DISABLE KEYS */;
/*!40000 ALTER TABLE `d_order_ticket_user_record_1` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `d_order_ticket_user_record_2`
--

DROP TABLE IF EXISTS `d_order_ticket_user_record_2`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `d_order_ticket_user_record_2` (
  `id` bigint(20) NOT NULL COMMENT '主键id',
  `order_number` bigint(20) NOT NULL COMMENT '订单编号',
  `identifier_id` bigint(20) DEFAULT NULL COMMENT '记录id',
  `ticket_user_order_id` bigint(20) NOT NULL COMMENT '购票人订单id',
  `program_id` bigint(20) NOT NULL COMMENT '节目表id',
  `user_id` bigint(20) NOT NULL COMMENT '用户id',
  `ticket_user_id` bigint(20) NOT NULL COMMENT '购票人id',
  `seat_id` bigint(20) NOT NULL COMMENT '座位id',
  `seat_info` varchar(100) DEFAULT NULL COMMENT '座位信息',
  `ticket_category_id` bigint(20) DEFAULT NULL COMMENT '节目票档id',
  `order_price` decimal(10,0) DEFAULT NULL COMMENT '订单价格',
  `record_type_code` int(3) DEFAULT NULL COMMENT '记录类型编码 -1:扣减余票 0:改变状态 1:增加余票',
  `record_type_value` varchar(256) DEFAULT NULL COMMENT '记录类型值 -1:扣减余票(reduce) 0:改变状态(changeStatus) 1:增加余票(increase)',
  `reconciliation_status` int(3) DEFAULT '1' COMMENT '对账状态 1:未对账 -1:对账完成有问题 2:对账完成没有问题 3:对账有问题处理完毕',
  `create_type` int(3) NOT NULL DEFAULT '1' COMMENT '创建类型 1:正常创建 2:补偿创建',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  KEY `d_order_ticket_user_record_order_id_IDX` (`order_number`) USING BTREE,
  KEY `d_order_ticket_user_record_ticket_user_order_id_IDX` (`ticket_user_order_id`) USING BTREE,
  KEY `d_order_ticket_user_record_user_id_IDX` (`user_id`) USING BTREE,
  KEY `d_order_ticket_user_record_ticket_user_id_IDX` (`ticket_user_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='购票人订单记录表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `d_order_ticket_user_record_2`
--

LOCK TABLES `d_order_ticket_user_record_2` WRITE;
/*!40000 ALTER TABLE `d_order_ticket_user_record_2` DISABLE KEYS */;
/*!40000 ALTER TABLE `d_order_ticket_user_record_2` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `d_order_ticket_user_record_3`
--

DROP TABLE IF EXISTS `d_order_ticket_user_record_3`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `d_order_ticket_user_record_3` (
  `id` bigint(20) NOT NULL COMMENT '主键id',
  `order_number` bigint(20) NOT NULL COMMENT '订单编号',
  `identifier_id` bigint(20) DEFAULT NULL COMMENT '记录id',
  `ticket_user_order_id` bigint(20) NOT NULL COMMENT '购票人订单id',
  `program_id` bigint(20) NOT NULL COMMENT '节目表id',
  `user_id` bigint(20) NOT NULL COMMENT '用户id',
  `ticket_user_id` bigint(20) NOT NULL COMMENT '购票人id',
  `seat_id` bigint(20) NOT NULL COMMENT '座位id',
  `seat_info` varchar(100) DEFAULT NULL COMMENT '座位信息',
  `ticket_category_id` bigint(20) DEFAULT NULL COMMENT '节目票档id',
  `order_price` decimal(10,0) DEFAULT NULL COMMENT '订单价格',
  `record_type_code` int(3) DEFAULT NULL COMMENT '记录类型编码 -1:扣减余票 0:改变状态 1:增加余票',
  `record_type_value` varchar(256) DEFAULT NULL COMMENT '记录类型值 -1:扣减余票(reduce) 0:改变状态(changeStatus) 1:增加余票(increase)',
  `reconciliation_status` int(3) DEFAULT '1' COMMENT '对账状态 1:未对账 -1:对账完成有问题 2:对账完成没有问题 3:对账有问题处理完毕',
  `create_type` int(3) NOT NULL DEFAULT '1' COMMENT '创建类型 1:正常创建 2:补偿创建',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  KEY `d_order_ticket_user_record_order_id_IDX` (`order_number`) USING BTREE,
  KEY `d_order_ticket_user_record_ticket_user_order_id_IDX` (`ticket_user_order_id`) USING BTREE,
  KEY `d_order_ticket_user_record_user_id_IDX` (`user_id`) USING BTREE,
  KEY `d_order_ticket_user_record_ticket_user_id_IDX` (`ticket_user_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='购票人订单记录表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `d_order_ticket_user_record_3`
--

LOCK TABLES `d_order_ticket_user_record_3` WRITE;
/*!40000 ALTER TABLE `d_order_ticket_user_record_3` DISABLE KEYS */;
/*!40000 ALTER TABLE `d_order_ticket_user_record_3` ENABLE KEYS */;
UNLOCK TABLES;

DROP TABLE IF EXISTS `d_order_program_0`;
CREATE TABLE `d_order_program_0` (
 `id` bigint NOT NULL COMMENT '主键id',
 `program_id` bigint NOT NULL COMMENT '节目id',
 `order_number` bigint NOT NULL COMMENT '订单编号',
 `identifier_id` bigint DEFAULT NULL COMMENT '记录id',
 `handle_status` tinyint(1) NOT NULL DEFAULT '1' COMMENT '处理状态 1:未处理 2:已处理',
 `create_time` datetime NOT NULL COMMENT '创建时间',
 `edit_time` datetime NOT NULL COMMENT '编辑时间',
 `status` tinyint(1) NOT NULL DEFAULT '1' COMMENT '1:正常 0:删除',
 PRIMARY KEY (`id`),
 KEY `order_program_program_id_idx` (`program_id`) USING BTREE,
 KEY `order_program_order_number_idx` (`order_number`) USING BTREE,
 KEY `order_program_identifier_id_idx` (`identifier_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='节目订单表';

DROP TABLE IF EXISTS `d_order_program_1`;
CREATE TABLE `d_order_program_1` (
 `id` bigint NOT NULL COMMENT '主键id',
 `program_id` bigint NOT NULL COMMENT '节目id',
 `order_number` bigint NOT NULL COMMENT '订单编号',
 `identifier_id` bigint DEFAULT NULL COMMENT '记录id',
 `handle_status` tinyint(1) NOT NULL DEFAULT '1' COMMENT '处理状态 1:未处理 2:已处理',
 `create_time` datetime NOT NULL COMMENT '创建时间',
 `edit_time` datetime NOT NULL COMMENT '编辑时间',
 `status` tinyint(1) NOT NULL DEFAULT '1' COMMENT '1:正常 0:删除',
 PRIMARY KEY (`id`),
 KEY `order_program_program_id_idx` (`program_id`) USING BTREE,
 KEY `order_program_order_number_idx` (`order_number`) USING BTREE,
 KEY `order_program_identifier_id_idx` (`identifier_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='节目订单表';

--
-- Table structure for table `undo_log`
--

DROP TABLE IF EXISTS `undo_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `undo_log` (
  `branch_id` bigint(20) NOT NULL COMMENT 'branch transaction id',
  `xid` varchar(128) NOT NULL COMMENT 'global transaction id',
  `context` varchar(128) NOT NULL COMMENT 'undo_log context,such as serialization',
  `rollback_info` longblob NOT NULL COMMENT 'rollback info',
  `log_status` int(11) NOT NULL COMMENT '0:normal status,1:defense status',
  `log_created` datetime(6) NOT NULL COMMENT 'create datetime',
  `log_modified` datetime(6) NOT NULL COMMENT 'modify datetime',
  PRIMARY KEY (`branch_id`,`xid`),
  UNIQUE KEY `ux_undo_log` (`xid`,`branch_id`),
  KEY `ix_log_created` (`log_created`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AT transaction mode undo table';
/*!40101 SET character_set_client = @saved_cs_client */;

DROP TABLE IF EXISTS `d_sharding_route_mapping`;
CREATE TABLE `d_sharding_route_mapping` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `logical_shard_id` int NOT NULL COMMENT '逻辑分片ID（0-1023）',
    `physical_database_suffix` varchar(64) NOT NULL COMMENT '物理数据库名后缀（适用于所有库类型）',
    `physical_table_suffix` int NOT NULL COMMENT '物理表后缀（0-7，适用于所有表类型）',
    `version` int NOT NULL DEFAULT '1' COMMENT '版本号（用于热更新）',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `edit_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `status` int NOT NULL DEFAULT '1',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_logical_shard_id` (`logical_shard_id`)
) ENGINE=InnoDB COMMENT='虚拟分片路由映射表';

truncate table d_sharding_route_mapping;
-- 物理分片0：{库前缀}_0.{表前缀}_0 → 虚拟分片0-127
INSERT INTO d_sharding_route_mapping (logical_shard_id, physical_database_suffix, physical_table_suffix)
SELECT
    n AS logical_shard_id,
    '0' AS physical_database_suffix,
    0 AS physical_table_suffix
FROM
    (SELECT 0 + a + b*10 + c*100 AS n
     FROM
         (SELECT 0 AS a UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
          UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) t1,
         (SELECT 0 AS b UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
          UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) t2,
         (SELECT 0 AS c UNION SELECT 1) t3
    ) numbers
WHERE n >= 0 AND n <= 127;

-- 物理分片1：{库前缀}_0.{表前缀}_1 → 虚拟分片128-255
INSERT INTO d_sharding_route_mapping (logical_shard_id, physical_database_suffix, physical_table_suffix)
SELECT
    n AS logical_shard_id,
    '0' AS physical_database_suffix,
    1 AS physical_table_suffix
FROM
    (SELECT 128 + a + b*10 + c*100 AS n
     FROM
         (SELECT 0 AS a UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
          UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) t1,
         (SELECT 0 AS b UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
          UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) t2,
         (SELECT 0 AS c UNION SELECT 1) t3
    ) numbers
WHERE n >= 128 AND n <= 255;

-- 物理分片2：{库前缀}_0.{表前缀}_2 → 虚拟分片256-383
INSERT INTO d_sharding_route_mapping (logical_shard_id, physical_database_suffix, physical_table_suffix)
SELECT
    n AS logical_shard_id,
    '0' AS physical_database_suffix,
    2 AS physical_table_suffix
FROM
    (SELECT 256 + a + b*10 + c*100 AS n
     FROM
         (SELECT 0 AS a UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
          UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) t1,
         (SELECT 0 AS b UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
          UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) t2,
         (SELECT 0 AS c UNION SELECT 1) t3
    ) numbers
WHERE n >= 256 AND n <= 383;

-- 物理分片3：{库前缀}_0.{表前缀}_3 → 虚拟分片384-511
INSERT INTO d_sharding_route_mapping (logical_shard_id, physical_database_suffix, physical_table_suffix)
SELECT
    n AS logical_shard_id,
    '0' AS physical_database_suffix,
    3 AS physical_table_suffix
FROM
    (SELECT 384 + a + b*10 + c*100 AS n
     FROM
         (SELECT 0 AS a UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
          UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) t1,
         (SELECT 0 AS b UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
          UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) t2,
         (SELECT 0 AS c UNION SELECT 1) t3
    ) numbers
WHERE n >= 384 AND n <= 511;

-- 物理分片4：{库前缀}_1.{表前缀}_0 → 虚拟分片512-639
INSERT INTO d_sharding_route_mapping (logical_shard_id, physical_database_suffix, physical_table_suffix)
SELECT
    n AS logical_shard_id,
    '1' AS physical_database_suffix,
    0 AS physical_table_suffix
FROM
    (SELECT 512 + a + b*10 + c*100 AS n
     FROM
         (SELECT 0 AS a UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
          UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) t1,
         (SELECT 0 AS b UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
          UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) t2,
         (SELECT 0 AS c UNION SELECT 1) t3
    ) numbers
WHERE n >= 512 AND n <= 639;

-- 物理分片5：{库前缀}_1.{表前缀}_1 → 虚拟分片640-767
INSERT INTO d_sharding_route_mapping (logical_shard_id, physical_database_suffix, physical_table_suffix)
SELECT
    n AS logical_shard_id,
    '1' AS physical_database_suffix,
    1 AS physical_table_suffix
FROM
    (SELECT 640 + a + b*10 + c*100 AS n
     FROM
         (SELECT 0 AS a UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
          UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) t1,
         (SELECT 0 AS b UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
          UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) t2,
         (SELECT 0 AS c UNION SELECT 1) t3
    ) numbers
WHERE n >= 640 AND n <= 767;

-- 物理分片6：{库前缀}_1.{表前缀}_2 → 虚拟分片768-895
INSERT INTO d_sharding_route_mapping (logical_shard_id, physical_database_suffix, physical_table_suffix)
SELECT
    n AS logical_shard_id,
    '1' AS physical_database_suffix,
    2 AS physical_table_suffix
FROM
    (SELECT 768 + a + b*10 + c*100 AS n
     FROM
         (SELECT 0 AS a UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
          UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) t1,
         (SELECT 0 AS b UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
          UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) t2,
         (SELECT 0 AS c UNION SELECT 1) t3
    ) numbers
WHERE n >= 768 AND n <= 895;

-- 物理分片7：{库前缀}_1.{表前缀}_3 → 虚拟分片896-1023
INSERT INTO d_sharding_route_mapping (logical_shard_id, physical_database_suffix, physical_table_suffix)
SELECT
    n AS logical_shard_id,
    '1' AS physical_database_suffix,
    3 AS physical_table_suffix
FROM
    (SELECT 896 + a + b*10 + c*100 AS n
     FROM
         (SELECT 0 AS a UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
          UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) t1,
         (SELECT 0 AS b UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
          UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) t2,
         (SELECT 0 AS c UNION SELECT 1) t3
    ) numbers
WHERE n >= 896 AND n <= 1023;