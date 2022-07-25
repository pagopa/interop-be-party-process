package it.pagopa.interop.partyprocess.service.impl

import it.pagopa.interop.partyprocess.model.UserRegistryUser
import it.pagopa.interop.partyprocess.server.impl.Dependencies
import it.pagopa.interop.partyprocess.service.SignatureValidationService
import org.scalatest.Ignore
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.nio.file.{Files, Path, Paths}
import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

@Ignore
class SignatureSpec extends AnyWordSpecLike with Matchers with Dependencies {

  "signature must work" in {

    val unsignedDocumentPath: Path = Paths.get("unsigned doc path")

    val signedDocumentPath: Path = Paths.get("signed doc path")

    val signedDocumentBytes: Array[Byte] = Files.readAllBytes(signedDocumentPath)

    assert(signedDocumentBytes.nonEmpty)

    val testResult: Future[String] = for {
      signatureService <- signatureService()
      unsignedDigest   <- signatureService.createDigest(unsignedDocumentPath.toFile)
      _ = println(s"Unsigned Digest: $unsignedDigest")
      validator <- signatureService.createDocumentValidator(signedDocumentBytes)
      _ <- SignatureValidationService.validateSignature(SignatureValidationServiceImpl.isDocumentSigned(validator))
      reports <- SignatureValidationServiceImpl.validateDocument(validator)
      _       <-
        SignatureValidationService.validateSignature(
          SignatureValidationServiceImpl.verifyOriginalDocument(validator),
          SignatureValidationServiceImpl.verifySignature(reports),
          SignatureValidationServiceImpl.verifyDigest(validator, unsignedDigest),
          SignatureValidationServiceImpl.verifyManagerTaxCode(
            reports,
            Seq(
              UserRegistryUser(id = UUID.randomUUID(), name = "", surname = "", taxCode = "tax code 1"),
              UserRegistryUser(id = UUID.randomUUID(), name = "", surname = "", taxCode = "tax code 2")
            )
          )
        )
    } yield "OK"

    val result = Await.result(testResult, Duration.Inf)

    assert(result == "OK")
  }

}
