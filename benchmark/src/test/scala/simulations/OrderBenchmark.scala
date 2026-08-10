package simulations

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._
import config.TicketFlowProtocol._
import scenarios.CreateOrderScenario
import scenarios.OpenLoopOrderScenario
import scenarios.OrderResultCounter

class OrderBenchmark extends Simulation {

  val version = sys.props.get("appVersion").getOrElse("v4")
  // 真并发：rampUsers 启动 N 个用户，每个用户 during(duration) 循环下单
  val concurrency = sys.props.get("concurrency").map(_.toInt).getOrElse(30)
  val duration = sys.props.get("duration").map(_.toInt).getOrElse(20)
  val resultsDir = sys.props.getOrElse("resultsDir", "results")
  // closed=闭环真并发（默认）；openloop=开环到达率（constantUsersPerSec）
  val mode = sys.props.getOrElse("mode", "closed")
  val rate = sys.props.get("rate").map(_.toInt).getOrElse(concurrency)

  println(s">>> Benchmark: version=$version, mode=$mode, concurrency=$concurrency, rate=$rate, duration=${duration}s")

  val pop = if (mode == "openloop") {
    // 开环：constantUsersPerSec 恒定到达率，每用户只下单一次
    OpenLoopOrderScenario(version)
      .inject(constantUsersPerSec(rate).during(duration.seconds))
      .protocols(httpProtocol)
  } else {
    CreateOrderScenario(version, concurrency, duration)
      .inject(
        rampUsers(concurrency).during(10.seconds)
      )
      .protocols(httpProtocol)
  }

  setUp(pop)

  after {
    val label =
      if (mode == "openloop") s"${version}-r${rate}-d${duration}"
      else s"${version}-c${concurrency}-d${duration}"
    OrderResultCounter.dump(label, resultsDir)
  }
}
