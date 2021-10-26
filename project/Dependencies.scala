import Versions._
import PDNDVersions._
import sbt._

object Dependencies {

  private[this] object akka {
    lazy val namespace   = "com.typesafe.akka"
    lazy val actorTyped  = namespace                       %% "akka-actor-typed"         % akkaVersion
    lazy val actor       = namespace                       %% "akka-actor"               % akkaVersion
    lazy val persistence = namespace                       %% "akka-persistence-typed"   % akkaVersion
    lazy val stream      = namespace                       %% "akka-stream"              % akkaVersion
    lazy val http        = namespace                       %% "akka-http"                % akkaHttpVersion
    lazy val httpJson    = namespace                       %% "akka-http-spray-json"     % akkaHttpVersion
    lazy val httpJson4s  = "de.heikoseeberger"             %% "akka-http-json4s"         % akkaHttpJson4sVersion
    lazy val management  = "com.lightbend.akka.management" %% "akka-management"          % "1.1.1"
    lazy val slf4j       = namespace                       %% "akka-slf4j"               % akkaVersion
    lazy val testkit     = namespace                       %% "akka-actor-testkit-typed" % akkaVersion
  }

  private[this] object pagopa {
    lazy val namespace = "it.pagopa"

    lazy val partyManagementClient =
      namespace %% "pdnd-interop-uservice-party-management-client" % partyManagementVersion

    lazy val partyProxyClient =
      namespace %% "pdnd-interop-uservice-party-registry-proxy-client" % partyProxyVersion

    lazy val attributeRegistryClient =
      namespace %% "pdnd-interop-uservice-attribute-registry-management-client" % attributeRegistryVersion

    lazy val authorizationProcessClient =
      namespace %% "pdnd-interop-uservice-authorization-process-client" % authorizationProcessVersion

    lazy val userRegistryManagementClient =
      namespace %% "pdnd-interop-uservice-user-registry-management-client" % userRegistryManagementVersion

  }

  private[this] object courier {
    lazy val namespace = "com.github.daddykotex"
    lazy val mail      = namespace %% "courier" % courierVersion

  }

  private[this] object itextpdf {
    lazy val namespace = "com.itextpdf"
    lazy val core      = namespace % "itext7-core" % iText7Version

  }

  private[this] object openapi4j {
    lazy val namespace          = "org.openapi4j"
    lazy val operationValidator = namespace % "openapi-operation-validator" % openapi4jVersion
  }

  private[this] object mustache {
    lazy val namespace = "com.github.spullara.mustache.java"
    lazy val compiler  = namespace % "compiler" % mustacheVersion
  }

  private[this] object json4s {
    lazy val namespace = "org.json4s"
    lazy val jackson   = namespace %% "json4s-jackson" % json4sVersion
    lazy val ext       = namespace %% "json4s-ext"     % json4sVersion
  }

  private[this] object logback {
    lazy val namespace = "ch.qos.logback"
    lazy val classic   = namespace % "logback-classic" % logbackVersion
  }

  private[this] object kamon {
    lazy val namespace  = "io.kamon"
    lazy val bundle     = namespace %% "kamon-bundle"     % kamonVersion
    lazy val prometheus = namespace %% "kamon-prometheus" % kamonVersion
  }

  private[this] object scalatest {
    lazy val namespace = "org.scalatest"
    lazy val core      = namespace %% "scalatest" % scalatestVersion
  }

  private[this] object scalamock {
    lazy val namespace = "org.scalamock"
    lazy val core      = namespace %% "scalamock" % scalaMockVersion
  }

  private[this] object jackson {
    lazy val namespace   = "com.fasterxml.jackson.core"
    lazy val core        = namespace % "jackson-core"        % jacksonVersion
    lazy val annotations = namespace % "jackson-annotations" % jacksonVersion
    lazy val databind    = namespace % "jackson-databind"    % jacksonVersion
  }

  private[this] object azure {
    lazy val namespace   = "com.azure"
    lazy val storageBlob = namespace % "azure-storage-blob" % azureStorageBlobVersion
  }

  private[this] object awssdk {
    lazy val namespace = "software.amazon.awssdk"
    lazy val s3        = namespace % "s3" % awsSdkVersion
  }

  object Jars {
    lazy val overrides: Seq[ModuleID] =
      Seq(jackson.annotations % Compile, jackson.core % Compile, jackson.databind % Compile)

    lazy val `server`: Seq[ModuleID] = Seq(
      // For making Java 12 happy
      "javax.annotation" % "javax.annotation-api" % "1.3.2" % "compile",
      //
      akka.actorTyped                     % Compile,
      akka.actor                          % Compile,
      akka.persistence                    % Compile,
      akka.stream                         % Compile,
      akka.http                           % Compile,
      akka.httpJson                       % Compile,
      akka.management                     % Compile,
      openapi4j.operationValidator        % Compile,
      mustache.compiler                   % Compile,
      pagopa.partyManagementClient        % Compile,
      pagopa.partyProxyClient             % Compile,
      pagopa.attributeRegistryClient      % Compile,
      pagopa.authorizationProcessClient   % Compile,
      pagopa.userRegistryManagementClient % Compile,
      courier.mail                        % Compile,
      itextpdf.core                       % Compile,
      logback.classic                     % Compile,
      akka.slf4j                          % Compile,
      kamon.bundle                        % Compile,
      kamon.prometheus                    % Compile,
      azure.storageBlob                   % Compile,
      awssdk.s3                           % Compile,
      akka.testkit                        % Test,
      scalatest.core                      % Test,
      scalamock.core                      % Test
    )
    lazy val client: Seq[ModuleID] =
      Seq(
        akka.stream     % Compile,
        akka.http       % Compile,
        akka.httpJson4s % Compile,
        json4s.jackson  % Compile,
        json4s.ext      % Compile
      )
  }
}
