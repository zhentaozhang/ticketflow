package com.couponseckill.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 优惠券秒杀压测（对齐 ticketflow benchmark 口径，docs/01-技术设计.md M4）：
 * - 开环恒定到达率：constantRate 请求/秒，持续 duration 秒，每用户只抢一次
 * - 主指标：端到端落库率 = DB 落库流水数 ÷ 请求数（Kafka lag=0 冷却后统计）
 * - 次级：受理成功率（HTTP 200 且 code=200）、p50/p95/p99/max 延迟、错误码分桶
 *
 * 用法：
 *   mvn -q exec:java -Dexec.mainClass=com.couponseckill.benchmark.BenchmarkMain \
 *     -Dexec.classpathScope=test -Dexec.args="--rate 120 --duration 60 --base 100000 --output benchmark-results/result-120.json"
 */
public class BenchmarkMain {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BASE_URL = "http://localhost:8090";
    private static final String KAFKA_BOOTSTRAP = "localhost:9092";
    private static final String CONSUMER_GROUP = "coupon-seckill";
    private static final String DB_URL = "jdbc:mysql://localhost:3306/coupon_seckill?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai";

    public static void main(String[] args) throws Exception {
        Map<String, String> params = parseArgs(args);
        int rate = Integer.parseInt(params.getOrDefault("rate", "120"));
        int duration = Integer.parseInt(params.getOrDefault("duration", "60"));
        long baseUserId = Long.parseLong(params.getOrDefault("base", "100000"));
        String output = params.getOrDefault("output", "benchmark-results/result.json");
        Long activityId = params.get("activityId") == null ? null : Long.parseLong(params.get("activityId"));

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        // 1) 准备活动（未指定则自动创建：充足库存避免售罄干扰容量判断）
        if (activityId == null) {
            activityId = createActivity(client, rate * duration * 2 + 1000);
            System.out.println("[benchmark] created activityId=" + activityId);
        }

        // 2) 开环灌压
        System.out.printf("[benchmark] rate=%d/s duration=%ds activityId=%d%n", rate, duration, activityId);
        long startNanos = System.nanoTime();
        long total = 0;
        AtomicLong accepted = new AtomicLong();
        AtomicLong httpErrors = new AtomicLong();
        List<Double> latencies = new ArrayList<>();
        Map<Integer, AtomicLong> errorBuckets = new HashMap<>();
        AtomicLong requestSeq = new AtomicLong();

        long firstSlotNanos = System.nanoTime();
        for (long i = 0; i < (long) rate * duration; i++) {
            long slot = i;
            // 恒定到达率：按 slot 计算目标发送时刻（异步发送不等响应，循环只控发送节奏）
            long targetNanos = firstSlotNanos + (slot * 1_000_000_000L / rate);
            long now = System.nanoTime();
            if (targetNanos > now) {
                sleepUntil(targetNanos);
            }
            long userId = baseUserId + requestSeq.incrementAndGet();
            fireAsync(client, activityId, userId, accepted, httpErrors, latencies, errorBuckets);
        }
        long totalDurationMs = (System.nanoTime() - startNanos) / 1_000_000;
        total = rate * (long) duration;

        // 等待所有异步响应回收（上限 5s）
        waitPending(pendingCounter, 5);

        // 3) 冷却闭环：等待 Kafka consumer lag=0（上限 90s）
        boolean drained = awaitLagZero(90);
        System.out.println("[benchmark] lag drained=" + drained);

        // 4) 落库统计（端到端主指标）
        long persisted = countPersisted(activityId);

        // 5) 结果
        double[] sorted = latencies.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        double p50 = percentile(sorted, 0.50);
        double p95 = percentile(sorted, 0.95);
        double p99 = percentile(sorted, 0.99);
        double max = sorted.length == 0 ? 0 : sorted[sorted.length - 1];

        Map<String, Object> result = new HashMap<>();
        result.put("rate", rate);
        result.put("duration", duration);
        result.put("activityId", activityId);
        result.put("requests", total);
        result.put("actualDurationMs", totalDurationMs);
        result.put("accepted", accepted.get());
        result.put("acceptRate", round(accepted.get() * 100.0 / total));
        result.put("persisted", persisted);
        result.put("e2eSuccessRate", round(persisted * 100.0 / total));
        result.put("p50Ms", round(p50));
        result.put("p95Ms", round(p95));
        result.put("p99Ms", round(p99));
        result.put("maxMs", round(max));
        result.put("httpErrors", httpErrors.get());
        Map<String, Object> buckets = new HashMap<>();
        errorBuckets.forEach((code, n) -> buckets.put(String.valueOf(code), n.get()));
        result.put("errorBuckets", buckets);
        result.put("lagDrained", drained);

        Files.createDirectories(Path.of(output).getParent());
        Files.writeString(Path.of(output), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        System.out.println("[benchmark] result written to " + output);
        System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result));
    }

    private static final AtomicLong pendingCounter = new AtomicLong();

    /** 异步发送：发送循环不等响应，回调中收集结果，保证恒定到达率 */
    private static void fireAsync(HttpClient client, Long activityId, long userId, AtomicLong accepted,
                                  AtomicLong httpErrors, List<Double> latencies, Map<Integer, AtomicLong> errorBuckets) {
        String requestId = UUID.randomUUID().toString();
        String body = "{\"activityId\":" + activityId + ",\"requestId\":\"" + requestId + "\"}";
        long t0 = System.nanoTime();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/flash-sale/grab"))
                .header("Content-Type", "application/json")
                .header("X-User-Id", String.valueOf(userId))
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        pendingCounter.incrementAndGet();
        client.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenAccept(resp -> {
                    try {
                        double ms = (System.nanoTime() - t0) / 1_000_000.0;
                        synchronized (latencies) {
                            latencies.add(ms);
                        }
                        if (resp.statusCode() == 200) {
                            JsonNode node = JSON.readTree(resp.body());
                            if (node.get("code").asInt() == 200) {
                                accepted.incrementAndGet();
                            } else {
                                errorBuckets.computeIfAbsent(node.get("code").asInt(), k -> new AtomicLong()).incrementAndGet();
                            }
                        } else {
                            httpErrors.incrementAndGet();
                        }
                    } catch (Exception e) {
                        httpErrors.incrementAndGet();
                    } finally {
                        pendingCounter.decrementAndGet();
                    }
                })
                .exceptionally(e -> {
                    httpErrors.incrementAndGet();
                    pendingCounter.decrementAndGet();
                    return null;
                });
    }

    /** sleep 到目标时刻：先睡到剩 2ms，再忙等到精确时刻（提高高 rate 下发送节奏精度） */
    private static void sleepUntil(long targetNanos) {
        long now = System.nanoTime();
        if (targetNanos - now > 2_000_000) {
            try {
                Thread.sleep((targetNanos - now - 2_000_000) / 1_000_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        while (System.nanoTime() < targetNanos) {
            // busy-wait 微调
        }
    }

    private static void waitPending(AtomicLong counter, int timeoutSeconds) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (counter.get() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
    }

    /** 轮询 consumer group 的 flash_sale_request 分区 lag 直到全部为 0 */
    private static boolean awaitLagZero(int timeoutSeconds) {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA_BOOTSTRAP);
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        try (AdminClient admin = AdminClient.create(props)) {
            while (System.currentTimeMillis() < deadline) {
                long lag = totalLag(admin);
                if (lag <= 0) {
                    return true;
                }
                Thread.sleep(2000);
            }
            return false;
        } catch (Exception e) {
            System.err.println("[benchmark] lag check failed: " + e.getMessage());
            return false;
        }
    }

    private static long totalLag(AdminClient admin) throws Exception {
        // 单组查询（兼容 kafka-clients 3.7 API）：已提交 offset vs 最新 offset
        var committed = admin.listConsumerGroupOffsets(CONSUMER_GROUP)
                .partitionsToOffsetAndMetadata().get();
        if (committed == null || committed.isEmpty()) {
            return 0;
        }
        var endOffsets = admin.listOffsets(committed.keySet().stream()
                .collect(java.util.stream.Collectors.toMap(p -> p, p -> OffsetSpec.latest()))).all().get();
        long total = 0;
        for (Map.Entry<TopicPartition, OffsetAndMetadata> e : committed.entrySet()) {
            long end = endOffsets.get(e.getKey()).offset();
            total += Math.max(0, end - e.getValue().offset());
        }
        return total;
    }

    private static long countPersisted(Long activityId) throws Exception {
        long total = 0;
        try (Connection conn = DriverManager.getConnection(DB_URL, "root", "");
             Statement st = conn.createStatement()) {
            for (int s = 0; s < 2; s++) {
                String sql = "SELECT COUNT(*) FROM flash_sale_order_" + s + " WHERE activity_id = " + activityId;
                try (ResultSet rs = st.executeQuery(sql)) {
                    if (rs.next()) {
                        total += rs.getLong(1);
                    }
                }
            }
        }
        return total;
    }

    /** 自动创建模板 + 活动（充足库存）并发布 */
    private static Long createActivity(HttpClient client, int stock) throws Exception {
        long templateId = post(client, "/manage/template",
                "{\"name\":\"压测券\",\"type\":1,\"amount\":20.00,\"minAmount\":100.00,\"validType\":2,\"validDays\":30}");
        long activityId = post(client, "/manage/activity",
                "{\"couponTemplateId\":" + templateId + ",\"activityName\":\"压测活动-" + UUID.randomUUID().toString().substring(0, 8)
                        + "\",\"startTime\":\"2026-08-18T00:00:00\",\"endTime\":\"2026-08-20T00:00:00\",\"totalStock\":"
                        + stock + ",\"perUserLimit\":100}");
        post(client, "/manage/activity/" + activityId + "/publish", null);
        return activityId;
    }

    private static long post(HttpClient client, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + path))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(5));
        if (body != null) {
            b.POST(HttpRequest.BodyPublishers.ofString(body));
        } else {
            b.POST(HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> resp = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode node = JSON.readTree(resp.body());
        if (node.get("code").asInt() != 200) {
            throw new IllegalStateException("API failed: " + path + " -> " + resp.body());
        }
        return node.get("data").get("id").asLong();
    }

    private static double percentile(double[] sorted, double p) {
        if (sorted.length == 0) {
            return 0;
        }
        int idx = (int) Math.ceil(p * sorted.length) - 1;
        return sorted[Math.max(0, idx)];
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < args.length - 1; i += 2) {
            String k = args[i];
            if (k.startsWith("--")) {
                map.put(k.substring(2), args[i + 1]);
            }
        }
        return map;
    }
}
