package it.pagopa.pdnd.interop.uservice.partyprocess.service

import eu.europa.esig.dss.service.crl.OnlineCRLSource
import eu.europa.esig.dss.service.http.commons.{CommonsDataLoader, FileCacheDataLoader}
import eu.europa.esig.dss.service.ocsp.OnlineOCSPSource
import eu.europa.esig.dss.spi.client.http.{DSSFileLoader, IgnoreDataLoader}
import eu.europa.esig.dss.spi.x509.CommonCertificateSource
import eu.europa.esig.dss.spi.x509.aia.DefaultAIASource
import eu.europa.esig.dss.tsl.alerts.detections.{
  LOTLLocationChangeDetection,
  OJUrlChangeDetection,
  TLExpirationDetection,
  TLSignatureErrorDetection
}
import eu.europa.esig.dss.tsl.alerts.handlers.log.{
  LogLOTLLocationChangeAlertHandler,
  LogOJUrlChangeAlertHandler,
  LogTLExpirationAlertHandler,
  LogTLSignatureErrorAlertHandler
}
import eu.europa.esig.dss.tsl.alerts.{LOTLAlert, TLAlert}
import eu.europa.esig.dss.tsl.cache.CacheCleaner
import eu.europa.esig.dss.tsl.function.OfficialJournalSchemeInformationURI
import eu.europa.esig.dss.tsl.job.TLValidationJob
import eu.europa.esig.dss.tsl.source.LOTLSource
import eu.europa.esig.dss.tsl.sync.AcceptAllStrategy
import eu.europa.esig.dss.validation.{CommonCertificateVerifier, SignedDocumentValidator}
import it.pagopa.pdnd.interop.uservice.partyprocess.common.system.ApplicationConfiguration

import java.io.File
import java.util
import scala.concurrent.Future

trait SignatureService {
  def createDigest(file: File): Future[String]
  def createDocumentValidator(bytes: Array[Byte]): Future[SignedDocumentValidator]
}

object SignatureService {

  final val certificateVerifier: CommonCertificateVerifier = new CommonCertificateVerifier

  certificateVerifier.setAIASource(new DefaultAIASource())
  certificateVerifier.setOcspSource(new OnlineOCSPSource())
  certificateVerifier.setCrlSource(new OnlineCRLSource())

  def getEuropeanLOTL: LOTLSource = {
    val lotlSource: LOTLSource = new LOTLSource()
    lotlSource.setUrl(ApplicationConfiguration.euListOfTrustedListsURL)
    lotlSource.setCertificateSource(new CommonCertificateSource())
    lotlSource.setSigningCertificatesAnnouncementPredicate(
      new OfficialJournalSchemeInformationURI(ApplicationConfiguration.euOfficialJournalUrl)
    )
    lotlSource.setPivotSupport(true)
    lotlSource
  }

  def offlineLoader: DSSFileLoader = {
    val offlineFileLoader = new FileCacheDataLoader()
    offlineFileLoader.setCacheExpirationTime(Long.MaxValue)
    offlineFileLoader.setDataLoader(new IgnoreDataLoader())
    offlineFileLoader.setFileCacheDirectory(tlCacheDirectory)
    offlineFileLoader
  }

  def onlineLoader: DSSFileLoader = {
    val onlineFileLoader = new FileCacheDataLoader()
    onlineFileLoader.setCacheExpirationTime(0)
    onlineFileLoader.setDataLoader(dataLoader)
    onlineFileLoader.setFileCacheDirectory(tlCacheDirectory)
    onlineFileLoader
  }

  def dataLoader: CommonsDataLoader = {
    new CommonsDataLoader()
  }

  def cacheCleaner: CacheCleaner = {
    val cacheCleaner: CacheCleaner = new CacheCleaner()
    cacheCleaner.setCleanMemory(true)
    cacheCleaner.setCleanFileSystem(true)
    cacheCleaner.setDSSFileLoader(offlineLoader)
    cacheCleaner
  }

  def tlCacheDirectory: File = {
    val rootFolder = new File(System.getProperty("java.io.tmpdir"))
    val tslCache   = new File(rootFolder, "dss-tsl-loader")
    if (tslCache.mkdirs()) {
      println(s"TL Cache folder : ${tslCache.getAbsolutePath}")
    }
    tslCache
  }

  def tlSigningAlert: TLAlert = {
    val signingDetection: TLSignatureErrorDetection = new TLSignatureErrorDetection()
    val handler: LogTLSignatureErrorAlertHandler    = new LogTLSignatureErrorAlertHandler()
    new TLAlert(signingDetection, handler)
  }

  def tlExpirationDetection: TLAlert = {
    val expirationDetection = new TLExpirationDetection()
    val handler             = new LogTLExpirationAlertHandler()
    new TLAlert(expirationDetection, handler)
  }

  def ojUrlAlert(source: LOTLSource): LOTLAlert = {
    val ojUrlDetection = new OJUrlChangeDetection(source)
    val handler        = new LogOJUrlChangeAlertHandler()
    new LOTLAlert(ojUrlDetection, handler)
  }

  def lotlLocationAlert(source: LOTLSource): LOTLAlert = {
    val lotlLocationDetection = new LOTLLocationChangeDetection(source)
    val handler               = new LogLOTLLocationChangeAlertHandler()
    new LOTLAlert(lotlLocationDetection, handler)
  }

  def getJob(lotl: LOTLSource): TLValidationJob = {
    val job: TLValidationJob = new TLValidationJob()

    job.setOfflineDataLoader(offlineLoader)
    job.setOnlineDataLoader(onlineLoader)
    job.setSynchronizationStrategy(new AcceptAllStrategy())
    job.setCacheCleaner(cacheCleaner)

    job.setListOfTrustedListSources(lotl)

    job.setLOTLAlerts(util.Arrays.asList(ojUrlAlert(lotl), lotlLocationAlert(lotl)))
    job.setTLAlerts(util.Arrays.asList(tlSigningAlert, tlExpirationDetection))

    job
  }

}
