package com.twitter.finatra.thrift.tests

import com.twitter.doeverything.thriftscala.DoEverything
import com.twitter.finagle.stats.InMemoryStatsReceiver
import com.twitter.finatra.thrift.{ThriftTest, EmbeddedThriftServer}
import com.twitter.finatra.thrift.tests.doeverything.DoEverythingThriftServer
import com.twitter.inject.WordSpecTest
import com.twitter.inject.server.PortUtils
import com.twitter.util.{Await, Future}

class MultiServerDarkTrafficFeatureTest extends WordSpecTest with ThriftTest {

  val darkDoEverythingThriftServer = new EmbeddedThriftServer(new DoEverythingThriftServer)
  val liveDoEverythingThriftServer = new EmbeddedThriftServer(
    new DoEverythingThriftServer,
    flags = Map(
      "thrift.dark.service.dest" -> s"/$$/inet/${PortUtils.loopbackAddress}/${darkDoEverythingThriftServer.thriftPort}",
      "thrift.dark.service.clientId" -> "client123"))

  lazy val client123 = liveDoEverythingThriftServer.thriftClient[DoEverything[Future]](clientId = "client123")

  // See DoEverythingThriftServerDarkTrafficFilterModule#enableSampling

  "magicNum is forwarded" in {
    Await.result(client123.magicNum()) should equal("26")

    // service stats
    liveDoEverythingThriftServer.assertCounter("per_method_stats/magicNum/success", 1)

    // darkTrafficFilter stats
    liveDoEverythingThriftServer.assertCounter("dark_traffic_filter/forwarded", 1)
    liveDoEverythingThriftServer.assertCounter("dark_traffic_filter/skipped", 0)

    darkDoEverythingThriftServer.assertHealthy() // give a chance for the stat to be recorded on the dark service
    // "dark" service stats
    darkDoEverythingThriftServer.assertCounter("per_method_stats/magicNum/success", 1)
  }

  "uppercase not forwarded" in {
    Await.result(client123.uppercase("hello")) should equal("HELLO")

    // service stats
    liveDoEverythingThriftServer.assertCounter("per_method_stats/uppercase/success", 1)
    // darkTrafficFilter stats
    liveDoEverythingThriftServer.assertCounter("dark_traffic_filter/forwarded", 0)
    liveDoEverythingThriftServer.assertCounter("dark_traffic_filter/skipped", 1)

    // "dark" service stats
    // no invocations on the doEverythingThriftServer1 as nothing is forwarded
    darkDoEverythingThriftServer.assertCounter("per_method_stats/uppercase/success", 0)
  }

  "echo is forwarded" in {
    Await.result(client123.echo("words")) should equal("words")

    // service stats
    liveDoEverythingThriftServer.assertCounter("per_method_stats/echo/success", 1)

    // darkTrafficFilter stats
    liveDoEverythingThriftServer.assertCounter("dark_traffic_filter/forwarded", 1)
    liveDoEverythingThriftServer.assertCounter("dark_traffic_filter/skipped", 0)

    darkDoEverythingThriftServer.assertHealthy() // give a chance for the stat to be recorded on the dark service
    // "dark" service stats
    darkDoEverythingThriftServer.assertCounter("per_method_stats/echo/success", 1)
  }

  "moreThanTwentyTwoArgs is not forwarded" in {
    Await.result(
      client123.moreThanTwentyTwoArgs(
        "one",
        "two",
        "three",
        "four",
        "five",
        "six",
        "seven",
        "eight",
        "nine",
        "ten",
        "eleven",
        "twelve",
        "thirteen",
        "fourteen",
        "fifteen",
        "sixteen",
        "seventeen",
        "eighteen",
        "nineteen",
        "twenty",
        "twentyone",
        "twentytwo",
        "twentythree")
    ) should equal("handled")

    // service stats
    liveDoEverythingThriftServer.assertCounter("per_method_stats/moreThanTwentyTwoArgs/success", 1)
    // darkTrafficFilter stats
    liveDoEverythingThriftServer.assertCounter("dark_traffic_filter/forwarded", 0)
    liveDoEverythingThriftServer.assertCounter("dark_traffic_filter/skipped", 1)

    // "dark" service stats
    // no invocations on the doEverythingThriftServer1 as nothing is forwarded
    darkDoEverythingThriftServer.assertCounter("per_method_stats/moreThanTwentyTwoArgs/success", 0)
  }

  override protected def beforeEach(): Unit = {
    darkDoEverythingThriftServer.statsReceiver.asInstanceOf[InMemoryStatsReceiver].clear()
    liveDoEverythingThriftServer.statsReceiver.asInstanceOf[InMemoryStatsReceiver].clear()
  }

  override def afterAll(): Unit = {
    darkDoEverythingThriftServer.close()
    liveDoEverythingThriftServer.close()
  }
}
