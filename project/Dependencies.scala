import Versions._
import PagopaVersions._
import sbt._

object Dependencies {

  private[this] object akka {
    lazy val namespace   = "com.typesafe.akka"
    lazy val actorTyped  = namespace           %% "akka-actor-typed"         % akkaVersion
    lazy val actor       = namespace           %% "akka-actor"               % akkaVersion
    lazy val persistence = namespace           %% "akka-persistence-typed"   % akkaVersion
    lazy val stream      = namespace           %% "akka-stream"              % akkaVersion
    lazy val http        = namespace           %% "akka-http"                % akkaHttpVersion
    lazy val httpJson    = namespace           %% "akka-http-spray-json"     % akkaHttpVersion
    lazy val httpJson4s  = "de.heikoseeberger" %% "akka-http-json4s"         % akkaHttpJson4sVersion
    lazy val slf4j       = namespace           %% "akka-slf4j"               % akkaVersion
    lazy val testkit     = namespace           %% "akka-actor-testkit-typed" % akkaVersion

    lazy val management          = "com.lightbend.akka.management" %% "akka-management" % akkaManagementVersion
    lazy val managementLogLevels =
      "com.lightbend.akka.management" %% "akka-management-loglevels-logback" % akkaManagementVersion
  }

  private[this] object pagopa {
    lazy val namespace = "it.pagopa"

    lazy val partyManagementClient =
      namespace %% "interop-be-party-management-client" % partyManagementVersion

    lazy val partyProxyClient =
      namespace %% "interop-be-party-registry-proxy-client" % partyProxyVersion

    lazy val commons     = namespace %% "interop-commons-utils"        % commonsVersion
    lazy val commonsMail = namespace %% "interop-commons-mail-manager" % commonsVersion
    lazy val commonsFile = namespace %% "interop-commons-file-manager" % commonsVersion
    lazy val commonsJWT  = namespace %% "interop-commons-jwt"          % commonsVersion

  }

  private[this] object selfcare {
    lazy val namespace = "it.pagopa.selfcare"

    lazy val selcArubaSigner = (namespace + ".soap") % "selc-commons-connector-soap-aruba-sign" % "2.4.3"
  }

  private[this] object europa {
    lazy val namespace = "eu.europa.ec.joinup.sd-dss"

    lazy val dssPades              = namespace % "dss-pades"                % dssVersion
    lazy val dssPadesPdfbox        = namespace % "dss-pades-pdfbox"         % dssVersion
    lazy val dssCades              = namespace % "dss-cades"                % dssVersion
    lazy val dssUtilsApacheCommons = namespace % "dss-utils-apache-commons" % dssVersion
    lazy val dssService            = namespace % "dss-service"              % dssVersion
    lazy val dssTlsValidation      = namespace % "dss-tsl-validation"       % dssVersion
    lazy val dssTCrlParserStreams  = namespace % "dss-crl-parser-stream"    % dssVersion
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

  private[this] object cats {
    lazy val namespace = "org.typelevel"
    lazy val core      = namespace %% "cats-core" % catsVersion
  }

  object Jars {

    lazy val `server`: Seq[ModuleID] = Seq(
      // For making Java 12 happy
      "javax.annotation"           % "javax.annotation-api" % "1.3.2" % "compile",
      "javax.xml.bind"             % "jaxb-api"             % "2.3.1" % "compile",
      //
      akka.actor                   % Compile,
      akka.actorTyped              % Compile,
      akka.http                    % Compile,
      akka.httpJson                % Compile,
      akka.management              % Compile,
      akka.managementLogLevels     % Compile,
      akka.persistence             % Compile,
      akka.slf4j                   % Compile,
      akka.stream                  % Compile,
      cats.core                    % Compile,
      europa.dssCades              % Compile,
      europa.dssPades              % Compile,
      europa.dssPadesPdfbox        % Compile,
      europa.dssUtilsApacheCommons % Compile,
      europa.dssService            % Compile,
      europa.dssTlsValidation      % Compile,
      europa.dssTCrlParserStreams  % Compile,
      kamon.bundle                 % Compile,
      kamon.prometheus             % Compile,
      logback.classic              % Compile,
      mustache.compiler            % Compile,
      pagopa.commons               % Compile,
      pagopa.commonsFile           % Compile,
      pagopa.commonsJWT            % Compile,
      pagopa.commonsMail           % Compile,
      pagopa.partyManagementClient % Compile,
      pagopa.partyProxyClient      % Compile,
      selfcare.selcArubaSigner     % Compile,
      akka.testkit                 % Test,
      scalatest.core               % Test,
      scalamock.core               % Test
    )
    lazy val client: Seq[ModuleID]   =
      Seq(akka.stream, akka.http, akka.slf4j, akka.httpJson4s, json4s.jackson, json4s.ext, pagopa.commons).map(
        _ % Compile
      )
  }
} 
