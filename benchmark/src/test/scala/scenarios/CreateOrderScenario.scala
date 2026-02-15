package scenarios

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import config.TicketFlowProtocol._
import feeders.TestDataFeeder._

object CreateOrderScenario {

  def apply(version: String) = {
    scenario(s"下单_${version}")
      .feed(programTicketFeeder)
      .feed(userFeeder)
      .exec(
        http(s"create_order_${version}")
          .post(s"/program/order/create/${version}")
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
          .check(bodyString.saveAs("responseBody"))
      )
  }
}
