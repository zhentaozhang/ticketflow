package com.ticketflow.toolkit;

import cn.hutool.core.date.SystemClock;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 雪花算法ID生成器。基于Twitter Snowflake算法的分布式唯一ID生成实现，
 * 由时间戳+workId+dataCenterId+序列号组成64位Long。
 */
@Slf4j
public class SnowflakeIdGenerator {

    private static final long BASIS_TIME = 1288834974657L;
    private final long workerIdBits = 5L;
    private final long datacenterIdBits = 5L;
    private final long maxWorkerId = -1L ^ (-1L << workerIdBits);
    private final long maxDatacenterId = -1L ^ (-1L << datacenterIdBits);

    private final long sequenceBits = 12L;
    private final long workerIdShift = sequenceBits;
    private final long datacenterIdShift = sequenceBits + workerIdBits;

    private final long timestampLeftShift = sequenceBits + workerIdBits + datacenterIdBits;
    private final long sequenceMask = -1L ^ (-1L << sequenceBits);

    private final long workerId;


    private final long datacenterId;

    private long sequence = 0L;

    private long lastTimestamp = -1L;

    private InetAddress inetAddress;

    public SnowflakeIdGenerator(WorkDataCenterId workDataCenterId) {
        if (Objects.nonNull(workDataCenterId.getDataCenterId())) {
            this.workerId = workDataCenterId.getWorkId();
            this.datacenterId = workDataCenterId.getDataCenterId();
        } else {
            this.datacenterId = getDatacenterId(maxDatacenterId);
            workerId = getMaxWorkerId(datacenterId, maxWorkerId);
            log.error("Redis work id allocation unavailable, fallback to MAC+PID: workId={}, datacenterId={}, collision risk across instances", workerId, datacenterId);
        }
    }

    protected long getMaxWorkerId(long datacenterId, long maxWorkerId) {
        StringBuilder mpid = new StringBuilder();
        mpid.append(datacenterId);
        String name = ManagementFactory.getRuntimeMXBean().getName();
        if (StringUtils.isNotBlank(name)) {
            mpid.append(name.split("@")[0]);
        }
        return (mpid.toString().hashCode() & 0xffff) % (maxWorkerId + 1);
    }

    protected long getDatacenterId(long maxDatacenterId) {
        long id = 0L;
        try {
            if (null == this.inetAddress) {
                this.inetAddress = InetAddress.getLocalHost();
            }
            NetworkInterface network = NetworkInterface.getByInetAddress(this.inetAddress);
            if (null == network) {
                id = 1L;
            } else {
                byte[] mac = network.getHardwareAddress();
                if (null != mac) {
                    id = ((0x000000FF & (long) mac[mac.length - 2]) | (0x0000FF00 & (((long) mac[mac.length - 1]) << 8))) >> 6;
                    id = id % (maxDatacenterId + 1);
                }
            }
        } catch (Exception e) {
            log.warn(" getDatacenterId: " + e.getMessage());
        }
        return id;
    }

    private static final long GENE_SEQUENCE_MASK = (1L << 6) - 1;

    //在这里处理了时钟回拨
    public long getBase() {
        return getBase(sequenceMask);
    }

    /**
     * 时钟回拨处理 + 序列号自增。序列掩码由调用方决定：
     * 标准雪花用 12 位（4096/ms），基因法订单号必须用 6 位（64/ms），
     * 否则 sequence 左移 6 位后会覆盖 workerId/dataCenterId 位段，破坏订单号唯一性。
     */
    private long getBase(long sequenceMask) {
        int five = 5;
        long timestamp = timeGen();
        //闰秒
        if (timestamp < lastTimestamp) {
            long offset = lastTimestamp - timestamp;
            if (offset <= five) {
                try {
                    wait(offset << 1);
                    timestamp = timeGen();
                    if (timestamp < lastTimestamp) {
                        throw new RuntimeException(String.format("Clock moved backwards.  Refusing to generate id for %d milliseconds", offset));
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            } else {
                throw new RuntimeException(String.format("Clock moved backwards.  Refusing to generate id for %d milliseconds", offset));
            }
        }

        if (lastTimestamp == timestamp) {
            // 相同毫秒内，序列号自增；耗尽（或继承自其他掩码的高值）则等待下一毫秒
            if (sequence >= sequenceMask) {
                timestamp = tilNextMillis(lastTimestamp);
            } else {
                sequence = (sequence + 1) & sequenceMask;
            }
        } else {
            // 不同毫秒内,序列号从 [1,64) 随机起点。
            // 扩展为 64 个起点:低流量时覆盖的基因组合从 2 种增至 63 种,
            // 缓解低 6 位基因(分库分表索引)在冷启动阶段的分布热点
            sequence = initialSequence();
        }

        lastTimestamp = timestamp;

        return timestamp;
    }

    public synchronized long nextId() {
        long timestamp = getBase();

        return ((timestamp - BASIS_TIME) << timestampLeftShift)
                | (datacenterId << datacenterIdShift)
                | (workerId << workerIdShift)
                | sequence;
    }

    /**
     * 【方案1】生成订单编号 - 固定预留6位基因位
     * 核心思想：预留足够多的基因位，支持未来扩容而无需修改生成逻辑
     * <p>
     * 基因位分配（6位可支持64种组合）：
     * - 当前：2库4表 = 8种组合，占用3位
     * - 最大支持：8库 × 8表 = 64种组合
     * - 或：4库 × 16表 = 64种组合
     * <p>
     * 订单号结构：[时间戳][数据中心ID][机器ID][序列号][userId后6位]
     * <p>
     * 扩容时只需修改分片算法配置，无需修改此方法
     *
     * @param userId 用户ID
     * @return 订单编号
     */
    public synchronized long getOrderNumber(long userId) {
        // 基因法序列掩码必须与基因位宽一致（6 位 = 64/ms），
        // 超出会覆盖 workerId/dataCenterId 位段导致订单号碰撞
        long timestamp = getBase(GENE_SEQUENCE_MASK);

        // 固定预留6位基因位，支持未来扩容
        // 6位 = 可支持最大 2^6 = 64 种分片组合（8库8表）
        long fixedGeneLength = 6L;

        // 创建基因掩码：0b111111 (6个1)
        long geneMask = (1L << fixedGeneLength) - 1;

        // 从用户ID中提取后6位作为基因
        long userGene = userId & geneMask;

        // 生成订单编号
        // 结构：[时间戳][数据中心ID][机器ID][序列号左移6位][基因6位]
        return ((timestamp - BASIS_TIME) << timestampLeftShift)
                | (datacenterId << datacenterIdShift)
                | (workerId << workerIdShift)
                | (sequence << fixedGeneLength)
                | userGene;
    }

    /**
     * 新毫秒的序列号随机起点。
     * 独立方法便于测试覆写固定值（如全 1/全 0 验证序列号边界）
     *
     * @return [1, 64) 的随机数，覆盖 6 位基因序列全部取值
     */
    protected long initialSequence() {
        return ThreadLocalRandom.current().nextLong(1, 64);
    }

    protected long tilNextMillis(long lastTimestamp) {
        long timestamp = timeGen();
        while (timestamp <= lastTimestamp) {
            timestamp = timeGen();
        }
        return timestamp;
    }

    protected long timeGen() {
        return SystemClock.now();
    }
}