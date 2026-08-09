package scenarios

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._
import config.TicketFlowProtocol._
import feeders.TestDataFeeder._
import feeders.SeatPool

object CreateOrderScenario {

  /**
   * @param version     版本端点 v1/v2/v3/v4
   * @param concurrency 并发用户数（座位分配步长 = concurrency + 1，必须与压测注入一致）
   * @param duration    稳态测量窗口（秒），ramp(10s) 之后的循环时长
   */
  def apply(version: String, concurrency: Int, duration: Int) = {
    scenario(s"下单_${version}")
      .feed(userFeeder)
      .exec { session => session.set("round", 0) }
      .during(duration.seconds) {
        // 计算本轮座位（先取 round 使用，再自增存回：第 1 轮 round=0）
        exec { session =>
          val userIndex = session("userIndex").validate[Int].toOption.getOrElse(0)
          val round = session("round").validate[Int].toOption.getOrElse(0)
          val seat = SeatPool.seat(userIndex, round, concurrency)
          session.setAll(
            "seatId" -> seat("seatId"),
            "ticketCategoryId" -> seat("ticketCategoryId"),
            "price" -> seat("price"),
            "rowCode" -> seat("rowCode"),
            "colCode" -> seat("colCode"),
            "round" -> (round + 1)
          )
        }
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
            // HTTP 200 且业务 code=0 才算成功；业务失败（HTTP 200 + 非0 code）→ KO
            .check(status.is(200))
            .check(jsonPath("$.code").saveAs("respCode"))
            .check(jsonPath("$.code").is("0"))
        )
        .exec { session =>
          val code = session("respCode").validate[String].toOption.getOrElse("NO_JSON")
          OrderResultCounter.record(code)
          // 关键：移除 respCode，防止 HTTP 层失败的请求残留上一轮的 code
          // （超时/5xx 时 saveAs 不执行，不清理会把超时误记为上次的 code，通常是 "0"）
          session.remove("respCode")
        }
      }
  }
}
