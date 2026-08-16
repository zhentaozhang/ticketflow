package scenarios

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import io.gatling.core.structure.PopulationBuilder
import config.TicketFlowProtocol._
import feeders.TestDataFeeder._

object VersionCompareScenario {

  private val versions = List("v1", "v2", "v3", "v4", "v5")

  def allScenarios: List[PopulationBuilder] = versions.map { v =>
    scenario(s"版本对比_${v}")
      .feed(programTicketFeeder)
      .feed(userFeeder)
      .exec(
        http(s"order_${v}")
          .post(pathPrefix + s"/program/order/create/$v")
          // Gatling 3.9+ StringBody 不解析 EL 模板 → 用 session 函数显式构造 body
          .body(StringBody { session =>
            for {
              programId <- session("programId").validate[String]
              userId <- session("userId").validate[String]
              ticketCategoryId <- session("ticketCategoryId").validate[String]
              ticketUserJson <- session("ticketUserJson").validate[String]
            } yield "{\"programId\": " + programId + ", \"userId\": " + userId +
              ", \"ticketCategoryId\": " + ticketCategoryId + ", \"ticketCount\": 1, " +
              "\"ticketUserIdList\": " + ticketUserJson + "}"
          }).asJson
          .check(status.is(200))
          .check(jsonPath("$.code").saveAs("respCode"))
          .check(jsonPath("$.code").is("0"))
      )
      .inject(
        rampUsers(20).during(5),
        constantUsersPerSec(20).during(20)
      )
      .protocols(httpProtocol)
  }
}
