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
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
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

    private static final int MYSQL_FIXED_PORT = 13306;

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withPassword("root");

    static {
        // shardingsphere-order-local.yaml（src/test/resources）静态指向 13306，故固定宿主端口
        mysql.setPortBindings(List.of(MYSQL_FIXED_PORT + ":3306"));
    }

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379);

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    @DynamicPropertySource
    static void dynamicProps(DynamicPropertyRegistry registry) {
        registry.add("spring.redis.host", redis::getHost);
        registry.add("spring.redis.port", () -> redis.getMappedPort(6379));
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
                "jdbc:mysql://127.0.0.1:" + MYSQL_FIXED_PORT + "/?useSSL=false&allowPublicKeyRetrieval=true",
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

    @Test
    void doCreate_真实写库并计数_成功下单() {
        Long userId = 20260808L;
        Long orderNumber = System.currentTimeMillis() * 10 + 1;

        String result = orderService.doCreate(buildDomain(userId, orderNumber));

        assertEquals(String.valueOf(orderNumber), result);

        Order order = orderMapper.selectOne(
                Wrappers.lambdaQuery(Order.class).eq(Order::getOrderNumber, orderNumber));
        assertNotNull(order);
        assertEquals(userId, order.getUserId());
        assertEquals("电子票", order.getDistributionMode());

        Long count = redisCache.get(
                RedisKeyBuild.createRedisKey(RedisKeyManage.ACCOUNT_ORDER_COUNT, userId, 10L),
                Long.class);
        assertEquals(2L, count);
    }

    @Test
    void doCreate_重复订单号_抛订单已存在() {
        Long orderNumber = System.currentTimeMillis() * 10 + 2;

        orderService.doCreate(buildDomain(300L, orderNumber));

        TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                () -> orderService.doCreate(buildDomain(300L, orderNumber)));
        assertEquals(BaseCode.ORDER_EXIST.getCode(), ex.getCode());
    }
}
