package feeders

import io.gatling.core.Predef._
import io.gatling.core.feeder.BatchableFeederBuilder

object TestDataFeeder {

  val programId = "9999"
  val ticketCategoryIds = Array("901", "902", "903", "904", "905", "906", "907")

  val csvFeeder: BatchableFeederBuilder[String] = csv("data/test-data.csv").circular

  val programTicketFeeder: Iterator[Map[String, String]] = Iterator.continually {
    val idx = (System.nanoTime() % ticketCategoryIds.length).toInt.abs
    Map(
      "programId" -> programId,
      "ticketCategoryId" -> ticketCategoryIds(idx)
    )
  }

  val userFeeder: Iterator[Map[String, String]] = Iterator.from(1).map { i =>
    Map("userId" -> i.toString, "ticketUserJson" -> s"""[{"id":$i}]""")
  }
}
