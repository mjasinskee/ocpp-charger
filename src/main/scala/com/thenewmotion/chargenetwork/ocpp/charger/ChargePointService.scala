package com.thenewmotion.chargenetwork.ocpp.charger

import com.typesafe.scalalogging.slf4j.LazyLogging
import com.thenewmotion.ocpp.messages._
import akka.actor.{Actor, ActorRef, Props}
import akka.util.Timeout
import akka.pattern.ask

import scala.concurrent.duration._
import scala.concurrent.Await
import java.util.concurrent.TimeoutException

import org.apache.commons.net.ftp.FTPSClient
import java.io.ByteArrayInputStream
import java.nio.charset.Charset

import com.thenewmotion.time.Imports.DateTime
import java.text.SimpleDateFormat
import java.net.URI

import scala.language.postfixOps

/**
 * Implementation of ChargePointService that just logs each method call on it and does nothing else
 */
class ChargePointService(chargerId: String, actor: ActorRef) extends ChargePoint with LazyLogging {
  val uploadActor = system.actorOf(Props[Uploader])
  val chargerActor = system.actorOf(Props[ChargerActor])

  def clearCache = ClearCacheRes(accepted = false)

  def remoteStartTransaction(req: RemoteStartTransactionReq) = {
    logger.info(s"Request form CentralSystem $req")
    chargerActor ! RemoteStartTransaction(req.idTag.toString, 1)
    RemoteStartTransactionRes(accepted = true)
  }

  def remoteStopTransaction(req: RemoteStopTransactionReq) = {
    logger.info(s"Request form CentralSystem $req")
    chargerActor ! RemoteStopTransaction(req.transactionId)
    RemoteStopTransactionRes(accepted = true)}

  def unlockConnector(req: UnlockConnectorReq) = UnlockConnectorRes(accepted = false)

  def getDiagnostics(req: GetDiagnosticsReq) = {
    val fileName = "test-getdiagnostics-upload"
    uploadActor ! UploadJob(req.location, fileName)
    GetDiagnosticsRes(Some(fileName))
  }

  def changeConfiguration(req: ChangeConfigurationReq) = ChangeConfigurationRes(ConfigurationStatus.NotSupported)

  def getConfiguration(req: GetConfigurationReq) = GetConfigurationRes(Nil, req.keys)

  def changeAvailability(req: ChangeAvailabilityReq) = ChangeAvailabilityRes(AvailabilityStatus.Rejected)

  def reset(req: ResetReq) = ResetRes(accepted = false)

  def updateFirmware(req: UpdateFirmwareReq) {}

  def sendLocalList(req: SendLocalListReq) = SendLocalListRes(UpdateStatus.NotSupportedValue)

  def getLocalListVersion = GetLocalListVersionRes(AuthListNotSupported)

  def dataTransfer(req: ChargePointDataTransferReq) = ChargePointDataTransferRes(DataTransferStatus.UnknownVendorId)

  def reserveNow(req: ReserveNowReq) = ReserveNowRes(Reservation.Rejected)

  def cancelReservation(req: CancelReservationReq) = CancelReservationRes(accepted = false)

  override def apply[REQ <: ChargePointReq, RES <:ChargePointRes](req: REQ)
                                                                 (implicit reqRes: ChargePointReqRes[REQ, RES]) = {
    implicit val timeout = Timeout(500 millis)
    val future = actor ? req
    val res = try Await.result(future, timeout.duration).asInstanceOf[RES] catch {
      case _: TimeoutException => super.apply(req)(reqRes)
    }
    logger.info(s"$chargerId\n\t>> $req\n\t<< $res")
    res
  }
}

class Uploader extends Actor with LazyLogging {
  def receive = {
    case UploadJob(location, filename) =>
      logger.debug("Uploader being run")
      val client = new FTPSClient()
      try {
        client.connect(location.getHost)
        val authPart = location.getAuthority
        val userAndPasswd = authPart.split("@")(0).split(":")
        val loggedIn = client.login(userAndPasswd(0), userAndPasswd(1))
        logger.debug(if (loggedIn) "Uploader logged in" else "FTP login failed")
        val dateTimeString = new SimpleDateFormat("yyyyMMddHHmmssz").format(DateTime.now.toDate)
        val remoteName = s"${location.getPath}/$filename.$dateTimeString"
        client.enterLocalPassiveMode()
        logger.debug(s"Storing file at $remoteName")
        val success = client.storeFile(remoteName, new ByteArrayInputStream("zlorg".getBytes(Charset.defaultCharset())))
        logger.info(s"Uploader completed ${if (success) "successfully: " else "with error: "} ${client.getReplyString}")
      } catch {
        case e: Exception => logger.error("Uploading diagnostics failed", e)
      }
  }
}

case class UploadJob(location: URI, filename: String)

case class RemoteStartTransaction(rfid: String, connector: Int)
case class RemoteStopTransaction(transactionId: Int)
