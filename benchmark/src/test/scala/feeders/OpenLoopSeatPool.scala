package feeders

import io.gatling.core.Predef._

import java.util.concurrent.atomic.AtomicInteger

/**
 * 开环压测座位池：从 data/seats-9999.csv 加载（run-single.sh 压测前从 MySQL 导出，含表头）。
 *
 * 与闭环 SeatPool 的差异：开环（constantUsersPerSec）没有稳定的 userIndex/round 概念，
 * 因此用全局原子游标分配唯一座位。
 *
 * 分配策略（关键）：
 * - 按票档分组；游标 i → 档位 = ticketCategoryIds(i % 7)，同时刻 7 档均匀打散
 *   （模拟真实多档混卖，避免所有请求集中打同一票档导致锁竞争被人为放大）
 * - 档内取池下标 (i / 7) % pool.length，同档座位不重复（直到池回绕）
 */
object OpenLoopSeatPool {

  private lazy val records: Seq[Map[String, Any]] =
    csv("data/seats-9999.csv").readRecords

  private lazy val byCategory: Map[String, Array[Map[String, Any]]] =
    records.groupBy(_("ticketCategoryId").toString).map { case (k, v) => k -> v.toArray }

  private val categoryIds = Array("901", "902", "903", "904", "905", "906", "907")

  private val cursor = new AtomicInteger(0)

  /**
   * 取下一条唯一座位记录（跨虚拟用户原子递增，7 档均匀分布，同档不重复）。
   * 记录字段：seatId / ticketCategoryId / price / rowCode / colCode（CSV 表头定义）
   */
  def nextSeat(): Map[String, Any] = {
    val i = cursor.getAndIncrement()
    val categoryId = categoryIds(i % categoryIds.length)
    val pool = byCategory(categoryId)
    pool((i / categoryIds.length) % pool.length)
  }
}
