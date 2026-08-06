package com.ticketflow.shardingsphere;

/**
 * 基因法分片位运算公共工具。
 * <p>
 * 统一分库/分表索引计算，供分片算法与迁移服务共用，避免多处实现漂移。
 * 位运算分片的正确性前提是分片数必须为 2 的幂（2^n）。
 */
public final class ShardingGeneUtils {

    private ShardingGeneUtils() {
    }

    /**
     * 校验分片数是否为 2 的幂，非 2 的幂抛异常（配置错误早暴露，避免静默错误路由）。
     *
     * @param count 分片数量
     * @param name  配置项名称（用于错误信息）
     */
    public static void checkPowerOfTwo(int count, String name) {
        if (count <= 0 || (count & (count - 1)) != 0) {
            throw new IllegalArgumentException(
                    String.format("%s 必须是 2 的幂（当前值: %d），位运算分片要求分片数为 2^n", name, count));
        }
    }

    /**
     * 计算表分片占用的 bit 位数（log2(tableCount)）。
     * <p>
     * 使用位运算代替 Math.log 浮点计算，避免精度问题导致的截断误差。
     *
     * @param tableCount 表总数（必须是 2 的幂）
     * @return 表基因位长度
     */
    public static long tableGeneLength(int tableCount) {
        checkPowerOfTwo(tableCount, "tableCount");
        return Long.numberOfTrailingZeros(tableCount);
    }

    /**
     * 计算表索引：取分片键的低 N 位（N = log2(tableCount)）。
     *
     * @param tableCount 表总数（必须是 2 的幂）
     * @param shardingKey 分片键（订单号或 userId，低 6 位包含相同基因）
     * @return 表索引
     */
    public static long tableIndex(int tableCount, long shardingKey) {
        return (tableCount - 1) & shardingKey;
    }

    /**
     * 计算库索引：跳过表基因位后取中高位（M = log2(databaseCount)）。
     * <p>
     * 基因位分布：低 N 位为表基因，紧接着的 M 位为库基因。
     *
     * @param databaseCount 数据库总数（必须是 2 的幂）
     * @param shardingKey   分片键（订单号或 userId，低 6 位包含相同基因）
     * @param tableCount    表总数（必须是 2 的幂）
     * @return 数据库索引
     */
    public static long databaseIndex(int databaseCount, long shardingKey, int tableCount) {
        long tableGeneLength = tableGeneLength(tableCount);
        return (databaseCount - 1) & (shardingKey >> tableGeneLength);
    }
}
