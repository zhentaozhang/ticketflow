USE ticketflow_order_1;

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

--
-- Tables for 8-shard expansion (gene: table = key & 7)
--

DROP TABLE IF EXISTS `d_order_4`;
CREATE TABLE `d_order_4` LIKE `d_order_0`;
DROP TABLE IF EXISTS `d_order_5`;
CREATE TABLE `d_order_5` LIKE `d_order_0`;
DROP TABLE IF EXISTS `d_order_6`;
CREATE TABLE `d_order_6` LIKE `d_order_0`;
DROP TABLE IF EXISTS `d_order_7`;
CREATE TABLE `d_order_7` LIKE `d_order_0`;

DROP TABLE IF EXISTS `d_order_ticket_user_4`;
CREATE TABLE `d_order_ticket_user_4` LIKE `d_order_ticket_user_0`;
DROP TABLE IF EXISTS `d_order_ticket_user_5`;
CREATE TABLE `d_order_ticket_user_5` LIKE `d_order_ticket_user_0`;
DROP TABLE IF EXISTS `d_order_ticket_user_6`;
CREATE TABLE `d_order_ticket_user_6` LIKE `d_order_ticket_user_0`;
DROP TABLE IF EXISTS `d_order_ticket_user_7`;
CREATE TABLE `d_order_ticket_user_7` LIKE `d_order_ticket_user_0`;

DROP TABLE IF EXISTS `d_order_ticket_user_record_4`;
CREATE TABLE `d_order_ticket_user_record_4` LIKE `d_order_ticket_user_record_0`;
DROP TABLE IF EXISTS `d_order_ticket_user_record_5`;
CREATE TABLE `d_order_ticket_user_record_5` LIKE `d_order_ticket_user_record_0`;
DROP TABLE IF EXISTS `d_order_ticket_user_record_6`;
CREATE TABLE `d_order_ticket_user_record_6` LIKE `d_order_ticket_user_record_0`;
DROP TABLE IF EXISTS `d_order_ticket_user_record_7`;
CREATE TABLE `d_order_ticket_user_record_7` LIKE `d_order_ticket_user_record_0`;

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

