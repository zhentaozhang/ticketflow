package com.ticketflow.service;

import com.ticketflow.core.RedisKeyManage;
import com.ticketflow.dto.ProgramOrderCreateDto;
import com.ticketflow.dto.SeatDto;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.redis.RedisCache;
import com.ticketflow.redis.RedisKeyBuild;
import com.ticketflow.vo.SeatVo;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 集成测试（IT）：真实 MySQL(ShardingSphere) + Redis + Kafka 下的节目下单链路。
 * 覆盖 V4 全异步路径 createNewAsync：Lua 原子扣减（余票/锁座）→ Kafka 建单消息 → 失败码映射。
 * 测试数据复用 sql/cloud 种子节目（programId=4 路由到 ds_0/*_0），
 * d_seat 无种子座位，由 @BeforeAll 补充插入。
 * 运行：mvn verify（failsafe 触发 *IT），本地与 CI 行为一致（Testcontainers）。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("it")
class ProgramOrderFlowIT {

    private static final int MYSQL_FIXED_PORT = 13307;
    private static final String CREATE_ORDER_TOPIC = "ticketflow-create_order";

    /** 种子节目：基因路由落 ds_0 / *_0 表 */
    private static final long PROGRAM_ID = 4L;
    /** 种子票档：普通票 199，remain=20 */
    private static final long NORMAL_TICKET_CATEGORY_ID = 6L;
    /** 种子票档：VIP票 299，remain=20 */
    private static final long VIP_TICKET_CATEGORY_ID = 7L;

    @Container
    static MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.0"))
            .withPassword("root");

    static {
        // shardingsphere-program-it.yaml（src/test/resources）静态指向 13307，故固定宿主端口
        mysql.setPortBindings(List.of(MYSQL_FIXED_PORT + ":3306"));
    }

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379);

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:3.7.0"));

    @DynamicPropertySource
    static void dynamicProps(DynamicPropertyRegistry registry) {
        // application.yml 中 ${spring.profiles.active} 占位符会展开为 local（property 值），
        // 导致默认加载 local yaml（3306/standalone），必须显式指向 it yaml（13307/testcontainers）
        registry.add("spring.datasource.url",
                () -> "jdbc:shardingsphere:classpath:shardingsphere-program-it.yaml");
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired
    private ProgramOrderService programOrderService;

    @Autowired
    private com.ticketflow.service.ProgramShowTimeService programShowTimeService;

    @BeforeEach
    void warmUpProgramCache() {
        // 真实路径中节目详情页会先预热 PROGRAM/PROGRAM_SHOW_TIME 缓存；
        // createNewAsync 的 buildCreateOrderParamV2 只读两级缓存（不查 DB），必须先行预热。
        // 不调用 getById（其内部 createProgramVo 会 RPC base-data-service，it 环境未启动），直接回填缓存。
        com.ticketflow.entity.ProgramShowTime showTime =
                programShowTimeService.selectProgramShowTimeByProgramIdMultipleCache(PROGRAM_ID);
        com.ticketflow.vo.ProgramVo programVo = new com.ticketflow.vo.ProgramVo();
        programVo.setId(PROGRAM_ID);
        programVo.setTitle("测试节目");
        programVo.setPlace("测试场馆");
        programVo.setItemPicture("test.jpg");
        programVo.setPermitChooseSeat(1);
        programVo.setShowTime(showTime.getShowTime());
        redisCache.set(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM, PROGRAM_ID), programVo,
                3600L, TimeUnit.SECONDS);
    }

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @BeforeAll
    static void initSchema() throws Exception {
        List<String> scripts = List.of(
                "1_ticketflow_cloud_create_database.sql",
                "ticketflow_program_0.sql",
                "ticketflow_program_1.sql");
        // allowMultiQueries：整脚本一次执行，由 MySQL 服务端解析分号（INSERT 内含 HTML 文本分号，客户端拆分不可行）
        try (Connection connection = DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:" + MYSQL_FIXED_PORT
                        + "/?useSSL=false&allowPublicKeyRetrieval=true&allowMultiQueries=true",
                "root", "root");
             Statement statement = connection.createStatement()) {
            for (String script : scripts) {
                statement.execute(Files.readString(findScript(script)));
            }
            // d_seat 无 programId=4 的种子座位，补充 2 个普通票档座位（1排1列/1排2列，199 元，未售卖）
            statement.execute("INSERT INTO ticketflow_program_0.d_seat_0 (id, program_id, ticket_category_id, row_code, col_code, "
                    + "seat_type, price, sell_status, create_time, edit_time, status) VALUES "
                    + "(1, 4, 6, 1, 1, 1, 199, 1, NOW(), NOW(), 1), "
                    + "(2, 4, 6, 1, 2, 1, 199, 1, NOW(), NOW(), 1)");
            statement.execute("COMMIT");
        }
    }

    private static Path findScript(String name) {
        List<String> candidates = List.of(
                Path.of("sql/cloud", name).toString(),
                Path.of("../sql/cloud", name).toString(),
                Path.of("../../sql/cloud", name).toString());
        for (String candidate : candidates) {
            File file = new File(candidate);
            if (file.exists()) {
                return file.toPath();
            }
        }
        throw new IllegalStateException("找不到 sql 脚本: " + name + "，工作目录: " + System.getProperty("user.dir"));
    }

    @Test
    void createNewAsync_选座成功_余票扣减座位锁定并发送kafka() throws Exception {
        Long userId = 20260808L;
        List<SeatDto> seatDtoList = List.of(
                seat(1L, NORMAL_TICKET_CATEGORY_ID, 1, 1, new BigDecimal("199")),
                seat(2L, NORMAL_TICKET_CATEGORY_ID, 1, 2, new BigDecimal("199")));

        String orderNumber = programOrderService.createNewAsync(
                request(PROGRAM_ID, userId, List.of(1001L, 1002L), seatDtoList, null, null), 1);

        assertNotNull(orderNumber);

        // 余票 20 -> 18
        Object remain = stringRedisTemplate.opsForHash().get(
                RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_TICKET_REMAIN_NUMBER_HASH_RESOLUTION,
                        PROGRAM_ID, NORMAL_TICKET_CATEGORY_ID).getRelKey(),
                String.valueOf(NORMAL_TICKET_CATEGORY_ID));
        assertEquals("18", remain);

        // 座位 1-1/1-2 从 no_sold 移入 lock
        RedisKeyBuild noSoldKey = RedisKeyBuild.createRedisKey(
                RedisKeyManage.PROGRAM_SEAT_NO_SOLD_RESOLUTION_HASH, PROGRAM_ID, NORMAL_TICKET_CATEGORY_ID);
        RedisKeyBuild lockKey = RedisKeyBuild.createRedisKey(
                RedisKeyManage.PROGRAM_SEAT_LOCK_RESOLUTION_HASH, PROGRAM_ID, NORMAL_TICKET_CATEGORY_ID);
        assertNull(stringRedisTemplate.opsForHash().get(noSoldKey.getRelKey(), "1"));
        assertNotNull(stringRedisTemplate.opsForHash().get(lockKey.getRelKey(), "1"));

        // Kafka 建单消息（createOrderByMq 同步等待发送成功，方法返回时消息已在 broker）
        List<String> messages = consumeMessages(Duration.ofSeconds(10));
        assertEquals(1, messages.size());
        assertTrue(messages.get(0).contains(orderNumber));
    }

    @Test
    void createNewAsync_座位已锁定_抛座位锁定异常() {
        // 手动向 no_sold 注入 sellStatus=2 的座位 → Lua 命中 40002（SEAT_LOCK）
        SeatVo lockedSeat = new SeatVo();
        lockedSeat.setId(9001L);
        lockedSeat.setTicketCategoryId(VIP_TICKET_CATEGORY_ID);
        lockedSeat.setRowCode(9);
        lockedSeat.setColCode(1);
        lockedSeat.setPrice(new BigDecimal("299"));
        lockedSeat.setSellStatus(2);
        redisCache.putHash(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_SEAT_NO_SOLD_RESOLUTION_HASH,
                PROGRAM_ID, VIP_TICKET_CATEGORY_ID), "9001", lockedSeat, 60, TimeUnit.SECONDS);

        TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class, () -> programOrderService.createNewAsync(
                request(PROGRAM_ID, 1L, List.of(2001L),
                        List.of(seat(9001L, VIP_TICKET_CATEGORY_ID, 9, 1, new BigDecimal("299"))), null, null), 1));

        assertEquals(BaseCode.SEAT_LOCK.getCode(), ex.getCode());
    }

    @Test
    void createNewAsync_余票不足_抛余票数量不足异常() {
        // 21 张座位 > 票档 remain 20 → Lua 先校验余票（40011），未触碰座位检查
        List<SeatDto> seatDtoList = new ArrayList<>();
        List<Long> ticketUserIdList = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            seatDtoList.add(seat(9000L + i, VIP_TICKET_CATEGORY_ID, 9, 1 + i, new BigDecimal("299")));
            ticketUserIdList.add(3000L + i);
        }

        TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class, () -> programOrderService.createNewAsync(
                request(PROGRAM_ID, 1L, ticketUserIdList, seatDtoList, null, null), 1));

        assertEquals(BaseCode.TICKET_REMAIN_NUMBER_NOT_SUFFICIENT.getCode(), ex.getCode());
    }

    private static SeatDto seat(Long id, Long ticketCategoryId, int rowCode, int colCode, BigDecimal price) {
        SeatDto seatDto = new SeatDto();
        seatDto.setId(id);
        seatDto.setTicketCategoryId(ticketCategoryId);
        seatDto.setRowCode(rowCode);
        seatDto.setColCode(colCode);
        seatDto.setPrice(price);
        return seatDto;
    }

    private static ProgramOrderCreateDto request(Long programId, Long userId, List<Long> ticketUserIdList,
                                                 List<SeatDto> seatDtoList, Long ticketCategoryId, Integer ticketCount) {
        ProgramOrderCreateDto dto = new ProgramOrderCreateDto();
        dto.setProgramId(programId);
        dto.setUserId(userId);
        dto.setTicketUserIdList(ticketUserIdList);
        dto.setSeatDtoList(seatDtoList);
        dto.setTicketCategoryId(ticketCategoryId);
        dto.setTicketCount(ticketCount);
        return dto;
    }

    private List<String> consumeMessages(Duration timeout) {
        Properties props = new Properties();
        props.put("bootstrap.servers", kafka.getBootstrapServers());
        props.put("group.id", "it-consumer-" + System.nanoTime());
        props.put("key.deserializer", StringDeserializer.class.getName());
        props.put("value.deserializer", StringDeserializer.class.getName());
        props.put("auto.offset.reset", "earliest");
        List<String> messages = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(CREATE_ORDER_TOPIC));
            long deadline = System.currentTimeMillis() + timeout.toMillis();
            while (System.currentTimeMillis() < deadline && messages.isEmpty()) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                records.forEach(record -> messages.add(record.value()));
            }
        }
        return messages;
    }
}
