package scenarios

import java.nio.file.{Files, Paths}
import java.util.concurrent.ConcurrentHashMap

/**
 * 下单业务响应码分类计数器（跨虚拟用户共享）。
 * 业务失败 = HTTP 200 + 非 0 code，Gatling 只统计 HTTP 层；
 * 这里按业务 code 细分失败原因，供报告剔除"重复提交拦截"等人为模型产物。
 */
object OrderResultCounter {

  val counters = new ConcurrentHashMap[String, Long]()

  def record(code: String): Unit = {
    counters.merge(code, 1L, (a: Long, b: Long) => a + b)
  }

  def clear(): Unit = {
    counters.clear()
  }

  def dump(label: String, dir: String): Unit = {
    import scala.jdk.CollectionConverters._
    val data = counters.asScala.toMap
    val content = data.toSeq.sortBy(_._1).map { case (k, v) => s""""$k": $v""" }
      .mkString("{\n  ", ",\n  ", "\n}")
    val dirPath = Paths.get(dir)
    Files.createDirectories(dirPath)
    Files.write(dirPath.resolve(s"failure-$label.json"), content.getBytes("UTF-8"))
  }
}
