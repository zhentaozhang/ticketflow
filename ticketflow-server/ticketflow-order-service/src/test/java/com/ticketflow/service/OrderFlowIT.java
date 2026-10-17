package com.ticketflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ticketflow.domain.OrderCreateDomain;
import com.ticketflow.dto.OrderTicketUserCreateDto;
import com.ticketflow.entity.Order;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.mapper.OrderMapper;
import com.ticketflow.core.RedisKeyManage;
import com.ticketflow.redis.RedisCache;
import com.ticketflow.redis.RedisKeyBuild;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 集成测试（IT）：真实 MySQL(ShardingSphere) + Redis + Kafka 下的下单链路。
 * 覆盖：doCreate 写库（分库分表）→ 用户购票人数 Redis 计数 → 重复订单幂等拒绝。
 * 运行：mvn verify（failsafe 触发 *IT），本地与 CI 行为一致（Testcontainers）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@ActiveProfiles("it")
class OrderFlowIT {

    @Container
    static MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.0"))
            .withPassword("root");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379);

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:3.7.0"));

    @DynamicPropertySource
    static void dynamicProps(DynamicPropertyRegistry registry) {
        String yamlContent;
        try (var in = OrderFlowIT.class.getClassLoader().getResourceAsStream("shardingsphere-order-it.yaml")) {
            if (in == null) {
                throw new IllegalStateException("找不到 shardingsphere-order-it.yaml");
            }
            yamlContent = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("读取 shardingsphere-order-it.yaml 失败", e);
        }
        yamlContent = yamlContent.replaceAll("127\\.0\\.0\\.1:\\d+", "127.0.0.1:" + mysql.getFirstMappedPort());
        try {
            Path tempConfigFile = Files.createTempFile("shardingsphere-order-it-", ".yaml");
            tempConfigFile.toFile().deleteOnExit();
            Files.writeString(tempConfigFile, yamlContent);
            registry.add("spring.datasource.url",
                    () -> "jdbc:shardingsphere:absolutepath:" + tempConfigFile.toAbsolutePath());
        } catch (Exception e) {
            throw new RuntimeException("创建临时 ShardingSphere 配置文件失败", e);
        }
        // Boot 3.x 前缀 spring.data.redis.*（Redisson 与 RedisTemplate 均读取）
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private RedisCache redisCache;

    @BeforeAll
    static void initSchema() throws Exception {
        List<String> scripts = List.of(
                "1_ticketflow_cloud_create_database.sql",
                "ticketflow_order_0.sql",
                "ticketflow_order_1.sql");
        try (Connection connection = DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:" + mysql.getFirstMappedPort() + "/?useSSL=false&allowPublicKeyRetrieval=true",
                "root", "root");
             Statement statement = connection.createStatement()) {
            for (String script : scripts) {
                Path path = findScript(script);
                String sql = Files.readString(path);
                for (String stmt : splitStatements(sql)) {
                    if (!stmt.isEmpty()) {
                        statement.execute(stmt);
                    }
                }
            }
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

    private static List<String> splitStatements(String sql) {
        List<String> statements = new ArrayList<>();
        for (String stmt : sql.split(";")) {
            String trimmed = stmt.lines()
                    .map(String::strip)
                    .filter(line -> !line.startsWith("--"))
                    .reduce("", (acc, line) -> acc + line + "\n")
                    .trim();
            if (!trimmed.isEmpty()) {
                statements.add(trimmed);
            }
        }
        return statements;
    }

    private OrderCreateDomain buildDomain(Long userId, Long orderNumber) {
        OrderTicketUserCreateDto ticketUser = new OrderTicketUserCreateDto();
        ticketUser.setOrderNumber(orderNumber);
        ticketUser.setProgramId(10L);
        ticketUser.setUserId(userId);
        ticketUser.setTicketUserId(1001L);
        ticketUser.setSeatId(5001L);
        ticketUser.setSeatInfo("A区1排1座");

        OrderCreateDomain domain = new OrderCreateDomain();
        domain.setIdentifierId(90001L);
        domain.setOrderNumber(orderNumber);
        domain.setProgramId(10L);
        domain.setUserId(userId);
        domain.setProgramTitle("集成测试演出");
        domain.setProgramPermitChooseSeat(1);
        domain.setOrderPrice(new java.math.BigDecimal("300"));
        domain.setOrderTicketUserCreateDtoList(List.of(ticketUser, copyWithSeat(ticketUser, 5002L)));
        return domain;
    }

    private OrderTicketUserCreateDto copyWithSeat(OrderTicketUserCreateDto source, Long seatId) {
        OrderTicketUserCreateDto copy = new OrderTicketUserCreateDto();
        copy.setOrderNumber(source.getOrderNumber());
        copy.setProgramId(source.getProgramId());
        copy.setUserId(source.getUserId());
        copy.setTicketUserId(source.getTicketUserId());
        copy.setSeatId(seatId);
        copy.setSeatInfo("A区1排2座");
        return copy;
    }

    private static long geneOrderNumber(Long userId, long seq) {
        // 与 SnowflakeIdGenerator.getOrderNumber 基因规则一致：低 6 位 = userId 后 6 位，
        // 保证 order_number 与 user_id 在 ShardingSphere 基因路由中落同一库表
        long base = System.currentTimeMillis() * 100 + seq;
        return base & ~0x3FL | (userId & 0x3FL);
    }

    @Test
    void doCreate_真实写库_成功下单() {
        Long userId = 20260808L;
        Long orderNumber = geneOrderNumber(userId, 1);

        String result = orderService.doCreate(buildDomain(userId, orderNumber));

        assertEquals(String.valueOf(orderNumber), result);

        Order order = orderMapper.selectOne(
                Wrappers.lambdaQuery(Order.class).eq(Order::getOrderNumber, orderNumber));
        assertNotNull(order);
        assertEquals(userId, order.getUserId());
        assertEquals("电子票", order.getDistributionMode());

        // 限购计数已统一由 program 侧 Lua 脚本维护，doCreate 仅专注真实落库，不进行二次累加
        Long count = redisCache.get(
                RedisKeyBuild.createRedisKey(RedisKeyManage.ACCOUNT_ORDER_COUNT, userId, 10L),
                Long.class);
        assertNull(count);
    }

    @Test
    void doCreate_重复订单号_抛订单已存在() {
        Long orderNumber = geneOrderNumber(300L, 2);

        orderService.doCreate(buildDomain(300L, orderNumber));

        TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                () -> orderService.doCreate(buildDomain(300L, orderNumber)));
        assertEquals(BaseCode.ORDER_EXIST.getCode(), ex.getCode());
    }
}
