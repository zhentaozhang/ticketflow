package simulations

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._
import config.TicketFlowProtocol._
import scenarios.VersionCompareScenario

class VersionCompareSimulation extends Simulation {

  setUp(
    VersionCompareScenario.allScenarios
  )
}
