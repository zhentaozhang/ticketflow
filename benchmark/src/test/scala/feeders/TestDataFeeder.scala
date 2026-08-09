package feeders

import io.gatling.core.Predef._
import io.gatling.core.feeder.BatchableFeederBuilder

object TestDataFeeder {

  val programId = "9999"
  val ticketCategoryIds = Array("901", "902", "903", "904", "905", "906", "907")
  // 压测数据 id 范围：user/ticket_user 均从 3 开始（1、2 无数据），3..5000 共 4998 个
  val userStart = 3
  val userCount = 4998

  val csvFeeder: BatchableFeederBuilder[String] = csv("data/test-data.csv").circular

  val programTicketFeeder: Iterator[Map[String, String]] = Iterator.continually {
    val idx = (System.nanoTime() % ticketCategoryIds.length).toInt.abs
    Map(
      "programId" -> programId,
      "ticketCategoryId" -> ticketCategoryIds(idx)
    )
  }

  def singleTicketFeeder(ticketCategoryId: String): Iterator[Map[String, String]] = Iterator.continually {
    Map(
      "programId" -> programId,
      "ticketCategoryId" -> ticketCategoryId
    )
  }

  val userFeeder: Iterator[Map[String, String]] = Iterator.from(0).map { i =>
    val id = i % userCount + userStart
    // ticketUserIdList 服务端类型为 List<Long>，格式为 [id]
    Map("userId" -> id.toString, "ticketUserJson" -> s"""[$id]""", "userIndex" -> i.toString)
  }
}
