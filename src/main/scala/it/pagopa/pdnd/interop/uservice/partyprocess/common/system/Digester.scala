package it.pagopa.pdnd.interop.uservice.partyprocess.common.system

import java.io.File
import java.nio.file.Files
import java.security.{DigestInputStream, MessageDigest}

object Digester {
  def createHash(file: File): String = {
    val md  = MessageDigest.getInstance("MD5")
    val dis = new DigestInputStream(Files.newInputStream(file.toPath), md)
    while (dis.available > 0) { val _ = dis.read }
    dis.close()

    md.digest.map(b => String.format("%02x", Byte.box(b))).mkString

  }
}
