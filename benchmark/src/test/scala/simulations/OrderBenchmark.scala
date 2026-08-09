package simulations

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._
import config.TicketFlowProtocol._
import scenarios.CreateOrderScenario
import scenarios.OrderResultCounter

class OrderBenchmark extends Simulation {

  val version = sys.props.get("appVersion").getOrElse("v4")
  val targetQps = sys.props.get("targetQps").map(_.toInt).getOrElse(100)
  val duration = sys.props.get("duration").map(_.toInt).getOrElse(30)
  val resultsDir = sys.props.getOrElse("resultsDir", "results")

  println(s">>> Benchmark: version=$version, targetQps=$targetQps, duration=${duration}s")

  setUp(
    CreateOrderScenario(version)
      .inject(
        rampUsersPerSec(10).to(targetQps).during(10.seconds),
        constantUsersPerSec(targetQps).during(duration.seconds)
      )
      .protocols(httpProtocol)
  )

  after {
    OrderResultCounter.dump(version, resultsDir)
  }
}
