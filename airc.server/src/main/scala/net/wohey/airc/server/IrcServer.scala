package net.wohey.airc.server

import java.net.InetSocketAddress

import akka.actor.ActorSystem
import akka.event.slf4j.SLF4JLogging
import akka.io.IO
import akka.pattern.ask
import akka.stream.io.StreamTcp
import akka.stream.io.StreamTcp.IncomingTcpConnection
import akka.stream.scaladsl.Flow
import akka.stream.{FlowMaterializer, MaterializerSettings}
import akka.util.{ByteString, Timeout}
import net.wohey.airc.parser.IrcMessageParser

import scala.concurrent.{Promise, Future}
import scala.concurrent.duration._
import scala.util.{Try, Failure, Success}

class IrcServer(system: ActorSystem, val serverAddress: InetSocketAddress) extends SLF4JLogging {

  implicit val sys = system

  implicit val executionContext = system.dispatcher

  val settings = MaterializerSettings()

  val materializer = FlowMaterializer(settings)

  implicit val timeout = Timeout(5.seconds)

  val delimiter = ByteString("\r\n".getBytes("UTF-8"))

  def start() = {
    val serverFuture = IO(StreamTcp) ? StreamTcp.Bind(settings, serverAddress)

    serverFuture.onSuccess {
      case serverBinding: StreamTcp.TcpServerBinding =>
        log.info("Server started, listening on: " + serverBinding.localAddress)

        Flow(serverBinding.connectionStream).foreach { conn ⇒
          log.info(s"Client connected from: ${conn.remoteAddress}")
          createIncomingFlow(conn)
        }.consume(materializer)
    }

    val p = Promise[Boolean]()
    serverFuture.onComplete {
        case Failure(e) =>
          log.error(s"Server could not bind to $serverAddress: ${e.getMessage}")
          p.failure(e)
        case _ => p.success(true)
    }
    p.future
  }


  def createIncomingFlow(conn: IncomingTcpConnection) {
    val delimiterFraming = new DelimiterFraming(maxSize = 1000, delimiter = delimiter)
    Flow(conn.inputStream)
      .mapConcat(delimiterFraming.apply)
      .map(_.utf8String)
      .map(IrcMessageParser.parse)
      .filter(_.isSuccess)
      .map {
      case Success(m) => m
      case Failure(_) => throw new IllegalStateException("All failures should have been filtered already.")
    }
      .foreach(println(_))
      .consume(materializer)
  }
}