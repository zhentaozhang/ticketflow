package scenarios

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import config.TicketFlowProtocol._
import feeders.TestDataFeeder._
import feeders.OpenLoopSeatPool

object OpenLoopOrderScenario {

  /**
   * 开环下单场景：每个虚拟用户只执行一次下单，注入速率由 constantUsersPerSec 控制。
   * 座位由 OpenLoopSeatPool 全局游标唯一分配（不重复取座）。
   *
   * @param version 版本端点 v1/v2/v3/v4
   */
  def apply(version: String) = {
    scenario(s"开环下单_${version}")
      .feed(userFeeder)
      .exec { session =>
        val seat = OpenLoopSeatPool.nextSeat()
        session.setAll(
          "seatId" -> seat("seatId"),
          "ticketCategoryId" -> seat("ticketCategoryId"),
          "price" -> seat("price"),
          "rowCode" -> seat("rowCode"),
          "colCode" -> seat("colCode")
        )
      }
      .exec(
        http(s"create_order_${version}")
          .post(pathPrefix + s"/program/order/create/$version")
          .body(StringBody { session =>
            for {
              programId <- session("programId").validate[String]
              userId <- session("userId").validate[String]
              ticketCategoryId <- session("ticketCategoryId").validate[String]
              ticketUserJson <- session("ticketUserJson").validate[String]
              seatId <- session("seatId").validate[String]
              price <- session("price").validate[String]
              rowCode <- session("rowCode").validate[String]
              colCode <- session("colCode").validate[String]
            } yield "{\"programId\": " + programId + ", \"userId\": " + userId +
              ", \"ticketCategoryId\": " + ticketCategoryId + ", \"ticketCount\": 1, " +
              "\"ticketUserIdList\": " + ticketUserJson + ", " +
              "\"seatDtoList\": [{\"id\": " + seatId + ", \"ticketCategoryId\": " + ticketCategoryId +
              ", \"rowCode\": " + rowCode + ", \"colCode\": " + colCode + ", \"price\": " + price + "}]}"
          }).asJson
          .check(status.is(200))
          .check(jsonPath("$.code").saveAs("respCode"))
          .check(jsonPath("$.code").is("0"))
      )
      .exec { session =>
        val code = session("respCode").validate[String].toOption.getOrElse("NO_JSON")
        OrderResultCounter.record(code)
        // 移除 respCode，防止 HTTP 层失败的请求残留上一轮的 code
        session.remove("respCode")
      }
  }
}
