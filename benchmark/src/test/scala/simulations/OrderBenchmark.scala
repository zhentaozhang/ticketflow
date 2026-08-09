package simulations

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._
import config.TicketFlowProtocol._
import scenarios.CreateOrderScenario
import scenarios.OrderResultCounter

class OrderBenchmark extends Simulation {

  val version = sys.props.get("appVersion").getOrElse("v4")
  // 真并发：rampUsers 启动 N 个用户，每个用户 during(duration) 循环下单
  val concurrency = sys.props.get("concurrency").map(_.toInt).getOrElse(30)
  val duration = sys.props.get("duration").map(_.toInt).getOrElse(20)
  val resultsDir = sys.props.getOrElse("resultsDir", "results")

  println(s">>> Benchmark: version=$version, concurrency=$concurrency, duration=${duration}s")

  setUp(
    CreateOrderScenario(version, concurrency, duration)
      .inject(
        rampUsers(concurrency).during(10.seconds)
      )
      .protocols(httpProtocol)
  )

  after {
    OrderResultCounter.dump(s"${version}-c${concurrency}-d${duration}", resultsDir)
  }
}
