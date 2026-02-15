package scenarios

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import io.gatling.core.structure.PopulationBuilder
import config.TicketFlowProtocol._
import feeders.TestDataFeeder._

object VersionCompareScenario {

  private val versions = List("v1", "v2", "v3", "v4")

  def allScenarios: List[PopulationBuilder] = versions.map { v =>
    scenario(s"版本对比_${v}")
      .feed(programTicketFeeder)
      .feed(userFeeder)
      .exec(
        http(s"order_${v}")
          .post(s"/program/order/create/${v}")
          .body(StringBody(
            """{
              "programId": "${programId}",
              "userId": ${userId},
              "ticketCategoryId": ${ticketCategoryId},
              "ticketCount": 1,
              "ticketUserIdList": ${ticketUserJson}
            }"""
          )).asJson
          .check(status.in(200, 500))
      )
      .inject(
        rampUsers(20).during(5),
        constantUsersPerSec(20).during(20)
      )
      .protocols(httpProtocol)
  }
}
