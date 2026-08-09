package feeders

import io.gatling.core.Predef._

/**
 * 选座座位池：从 data/seats-9999.csv 加载（run-single.sh 压测前从 MySQL 导出，含表头），
 * 按档位分组，按公式分配唯一座位。
 *
 * 分配公式：用户 userIndex 固定档位 901 + userIndex % 7；
 * 第 round 轮取 seats[(userIndex + round * (concurrency + 1)) % 池大小]。
 * 步长取 concurrency+1（与 10000/20000 互质）：同用户跨轮回访周期 = 池大小轮；
 * userIndex < 5000 < 池大小：同时刻不同用户取不同座位。
 */
object SeatPool {

  // Gatling 3.11 readRecords 返回 Record[Any]（CSV 值实际是 String）
  private lazy val records: Seq[Map[String, Any]] =
    csv("data/seats-9999.csv").readRecords

  private lazy val byCategory: Map[String, Array[Map[String, Any]]] =
    records.groupBy(_("ticketCategoryId").toString).map { case (k, v) => k -> v.toArray }

  def categoryForUser(userIndex: Int): String =
    TestDataFeeder.ticketCategoryIds(userIndex % TestDataFeeder.ticketCategoryIds.length)

  /**
   * 用户 userIndex 第 round 轮的座位记录。
   * 记录字段：seatId / ticketCategoryId / price / rowCode / colCode（CSV 表头定义）
   */
  def seat(userIndex: Int, round: Int, concurrency: Int): Map[String, Any] = {
    val pool = byCategory(categoryForUser(userIndex))
    val idx = (userIndex + round * (concurrency + 1)) % pool.length
    pool(idx)
  }
}
