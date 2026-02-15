package simulations

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._
import config.TicketFlowProtocol._
import scenarios.ProgramDetailScenario

class ReadBenchmark extends Simulation {

  setUp(
    ProgramDetailScenario.detail
      .inject(
        rampUsers(20).during(5.seconds),
        constantUsersPerSec(50).during(30.seconds),
        constantUsersPerSec(100).during(30.seconds),
        constantUsersPerSec(200).during(30.seconds)
      )
      .protocols(httpProtocol)
  )
}
