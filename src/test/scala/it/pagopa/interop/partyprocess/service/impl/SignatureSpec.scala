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

/**
 * Test instructions
 * This test is useful to verify the document signature validation.
 * These are the steps to run the test:
 *  - comment @Ignore annotation
 *  - comment import org.scalatest.Ignore
 *  - set unsignedDocumentPath
 *  - set signedDocumentPath
 *  - For each signature present, add a UserRegistryUser entry to the legals parameter 
 *    of the SignatureValidationServiceImpl.verifyManagerTaxCode function.
 *  - For each UserRegistryUser added, set the taxCode field using the tax code associated with the signature.
 *  - run command 'testOnly it.pagopa.interop.partyprocess.service.impl.SignatureSpec' from sbt repl
 */
@Ignore
class SignatureSpec extends AnyWordSpecLike with Matchers with Dependencies {

  "signature must work" in {

    // Set the path of the unsigned document
    val unsignedDocumentPath: Path = Paths.get("unsigned doc path")

    // Set the path of the signed document
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
          SignatureValidationServiceImpl.verifyManagerTaxCode( // To pass this test taxCode must be set with real ones
            reports,
            // This sequence must contain a UserRegistryUser entry for each signer contained in the document signature.
            // For each signature present, the taxCode field must be set with the real tax code associated with the signature.
            Seq(
              UserRegistryUser(
                id = UUID.randomUUID(),
                name = Some(""),
                surname = Some(""),
                taxCode = Some("tax code 1")
              )
            )
          )
        )
    } yield "OK"

    val result = Await.result(testResult, Duration.Inf)

    result shouldBe "OK"
  }

}
