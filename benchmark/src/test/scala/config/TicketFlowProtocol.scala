package config

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import io.gatling.http.protocol.HttpProtocolBuilder

object TicketFlowProtocol {

  val httpProtocol: HttpProtocolBuilder = http
    .baseUrl("http://127.0.0.1:6086")
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")
    .userAgentHeader("TicketFlow-Benchmark")
    .disableCaching
    .silentResources

  val programServiceUrl = "http://127.0.0.1:6086"
  val userServiceUrl = "http://127.0.0.1:6082"

  val httpProtocolUser: HttpProtocolBuilder = http
    .baseUrl(userServiceUrl)
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")
    .userAgentHeader("TicketFlow-Benchmark")
    .disableCaching
    .silentResources
}
