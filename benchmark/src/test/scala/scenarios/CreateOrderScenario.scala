package scenarios

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import config.TicketFlowProtocol._
import feeders.TestDataFeeder._

object CreateOrderScenario {

  /**
   * @param version           版本端点 v1/v2/v3/v4
   * @param ticketCategoryId  指定票档（S1 单热点场景用），None 时随机 7 票档（S2）
   */
  def apply(version: String, ticketCategoryId: Option[String] = None) = {
    val ticketFeeder = ticketCategoryId match {
      case Some(catId) => singleTicketFeeder(catId)
      case None        => programTicketFeeder
    }
    scenario(s"下单_${version}")
      .feed(ticketFeeder)
      .feed(userFeeder)
      .exec(
        http(s"create_order_${version}")
          .post(pathPrefix + s"/program/order/create/$version")
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
          // HTTP 200 且业务 code=0 才算成功；业务失败（HTTP 200 + 非0 code）→ KO
          .check(status.is(200))
          .check(jsonPath("$.code").saveAs("respCode"))
          .check(jsonPath("$.code").is("0"))
      )
      .exec { session =>
        val code = session("respCode").validate[String].toOption.getOrElse("NO_JSON")
        OrderResultCounter.record(code)
        session
      }
  }
}
