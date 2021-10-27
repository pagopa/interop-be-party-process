package it.pagopa.pdnd.interop.uservice.partyprocess

import com.typesafe.config.{Config, ConfigFactory}

object SpecConfig {

  val testDataConfig: Config = ConfigFactory.parseString(s"""
      akka.coordinated-shutdown.terminate-actor-system = off
      akka.coordinated-shutdown.run-by-actor-system-terminate = off
      akka.coordinated-shutdown.run-by-jvm-shutdown-hook = off
      akka.cluster.run-coordinated-shutdown-when-down = off
    """)

  val config: Config = ConfigFactory
    .parseResourcesAnySyntax("application-test")
    .withFallback(testDataConfig)

  val port: Int = config.getInt("uservice-party-process.port")
}
