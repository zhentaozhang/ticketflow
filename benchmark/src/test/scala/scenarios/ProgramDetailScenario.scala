package scenarios

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import config.TicketFlowProtocol._

object ProgramDetailScenario {

  val detail = scenario("查询节目详情")
    .exec(
      http("program_detail")
        .post("/program/detail")
        .body(StringBody("""{"id":9999}""")).asJson
        .check(status.is(200))
        .check(jsonPath("$.data").exists)
    )
}
