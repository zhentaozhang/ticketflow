package config

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import io.gatling.http.protocol.HttpProtocolBuilder

object TicketFlowProtocol {

  // 直连 program-service 默认 6086；双实例专项经 gateway 6085（-DbaseUrl=http://127.0.0.1:6085）
  val baseUrl = sys.props.getOrElse("baseUrl", "http://127.0.0.1:6086")
  // gateway(pro profile) 路由 Path=/ticketflow/program/** + StripPrefix=2 → 经 gateway 需加 /ticketflow/program 前缀
  // 直连 6086 无前缀
  val pathPrefix = sys.props.getOrElse("pathPrefix", "")

  val httpProtocol: HttpProtocolBuilder = http
    .baseUrl(baseUrl)
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")
    .userAgentHeader("TicketFlow-Benchmark")
    .disableCaching
    .silentResources

  val programServiceUrl = baseUrl
  val userServiceUrl = "http://127.0.0.1:6082"

  val httpProtocolUser: HttpProtocolBuilder = http
    .baseUrl(userServiceUrl)
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")
    .userAgentHeader("TicketFlow-Benchmark")
    .disableCaching
    .silentResources
}
