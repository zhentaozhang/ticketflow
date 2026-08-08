package com.ticketflow.service;

import com.ticketflow.core.RedisKeyManage;
import com.ticketflow.dto.ProgramOrderCreateDto;
import com.ticketflow.dto.SeatDto;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.redis.RedisCache;
import com.ticketflow.redis.RedisKeyBuild;
import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 集成测试（IT）：Kafka 建单消息发送失败时，Redis 缓存自动回滚。
 * 覆盖 V4 异步路径 createNewAsync 的失败分支：Lua 原子扣减（余票/锁座）→ Kafka 发送失败
 * → createOrderByMq 失败回调调用 updateProgramCacheDataResolution(CANCEL) 反向恢复缓存 → 抛异常。
 * 通过 @MockBean 替换 KafkaTemplate 令 send 返回失败 future（不依赖真实 broker 故障，确定性触发）。
 * 运行：mvn verify（failsafe 触发 *IT），本地与 CI 行为一致（Testcontainers）。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("it")
class ProgramOrderKafkaFailureIT {

    private static final int MYSQL_FIXED_PORT = 13307;

    /** 种子节目：基因路由落 ds_0 / *_0 表（与 ProgramOrderFlowIT 一致） */
    private static final long PROGRAM_ID = 4L;
    /** 种子票档：普通票 199，remain=20，含 2 个种子座位（id 1/2） */
    private static final long NORMAL_TICKET_CATEGORY_ID = 6L;

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

    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @BeforeEach
    void mockKafkaSendFailure() {
        when(kafkaTemplate.send(anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new KafkaException("mock send failure")));
    }

    @Autowired
    private ProgramOrderService programOrderService;

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

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
    void createNewAsync_kafka发送失败_缓存回滚并抛异常() {
        Long userId = 20260808L;
        List<SeatDto> seatDtoList = List.of(
                seat(1L, NORMAL_TICKET_CATEGORY_ID, 1, 1, new BigDecimal("199")),
                seat(2L, NORMAL_TICKET_CATEGORY_ID, 1, 2, new BigDecimal("199")));

        // createOrderOperateProgramCacheResolution 会先预热 remain/seat 缓存（从 DB 读），
        // 预热后 remain=20（种子），座位 1/2 处于 NO_SOLD
        TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class, () -> programOrderService.createNewAsync(
                request(PROGRAM_ID, userId, List.of(1001L, 1002L), seatDtoList, null, null), 1));
        assertNotNull(ex);

        // 回滚断言：余票恢复 20（扣减 2 后回补）
        Object remain = stringRedisTemplate.opsForHash().get(
                RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_TICKET_REMAIN_NUMBER_HASH_RESOLUTION,
                        PROGRAM_ID, NORMAL_TICKET_CATEGORY_ID).getRelKey(),
                String.valueOf(NORMAL_TICKET_CATEGORY_ID));
        assertEquals("20", remain);

        // 回滚断言：座位 1/2 从 lock 移回 no_sold
        RedisKeyBuild noSoldKey = RedisKeyBuild.createRedisKey(
                RedisKeyManage.PROGRAM_SEAT_NO_SOLD_RESOLUTION_HASH, PROGRAM_ID, NORMAL_TICKET_CATEGORY_ID);
        RedisKeyBuild lockKey = RedisKeyBuild.createRedisKey(
                RedisKeyManage.PROGRAM_SEAT_LOCK_RESOLUTION_HASH, PROGRAM_ID, NORMAL_TICKET_CATEGORY_ID);
        assertNotNull(stringRedisTemplate.opsForHash().get(noSoldKey.getRelKey(), "1"));
        assertNotNull(stringRedisTemplate.opsForHash().get(noSoldKey.getRelKey(), "2"));
        assertNull(stringRedisTemplate.opsForHash().get(lockKey.getRelKey(), "1"));
        assertNull(stringRedisTemplate.opsForHash().get(lockKey.getRelKey(), "2"));
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
}
