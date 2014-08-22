package net.wohey.airc.server

import java.net.InetSocketAddress

import akka.actor._
import akka.io.IO
import akka.stream.io.StreamTcp
import akka.stream.io.StreamTcp.IncomingTcpConnection
import akka.stream.scaladsl.Flow
import akka.stream.{FlowMaterializer, MaterializerSettings}
import akka.util.{ByteString, Timeout}
import net.wohey.airc.parser.IrcMessageParser
import net.wohey.airc.server.Connection.IncomingFlowClosed

import scala.concurrent.duration._
import scala.util.{Failure, Success}

class IrcServer(shutdownSystemOnError : Boolean = false) extends Actor with ActorLogging {

  implicit val system = context.system

  val bindAddress = context.system.settings.config.getString("ircserver.socket.bind_adress")

  val port = context.system.settings.config.getInt("ircserver.socket.port")

  val serverAddress = new InetSocketAddress(bindAddress, port)

  val settings = MaterializerSettings()

  val materializer = FlowMaterializer(settings)

  implicit val timeout = Timeout(5.seconds)

  val delimiter = ByteString("\r\n".getBytes("UTF-8"))

  override def preStart() = {
    IO(StreamTcp) ! StreamTcp.Bind(settings, serverAddress)
  }

  def receive: Receive = {
    case serverBinding: StreamTcp.TcpServerBinding =>
      log.info("Server started, listening on: " + serverBinding.localAddress)
      createConnectionFlow(serverBinding)
    case Status.Failure(e)  =>
      log.error(e, s"Server could not bind to $serverAddress: ${e.getMessage}")
      if(shutdownSystemOnError) system.shutdown()
  }

  def createConnectionFlow(serverBinding: StreamTcp.TcpServerBinding) {
    Flow(serverBinding.connectionStream)
      .foreach { connection ⇒
        log.info(s"Client connected from: ${connection.remoteAddress}")
        handleNewConnection(connection)
      }
      .consume(materializer)
  }

  def handleNewConnection(connection: IncomingTcpConnection) {
    val connectionActor = context.actorOf(Props(new Connection(connection.remoteAddress)))
    createIncomingFlow(connection, connectionActor)
  }

  def createIncomingFlow(connection: IncomingTcpConnection, connectionActor : ActorRef) {
    val delimiterFraming = new DelimiterFraming(maxSize = 1000, delimiter = delimiter)
    Flow(connection.inputStream)
      .mapConcat(delimiterFraming.apply)
      .map(_.utf8String)
      .map(IrcMessageParser.parse)
      .filter(_.isSuccess)
      .map {
        case Success(m) => m
        case Failure(_) => throw new IllegalStateException("All failures should have been filtered already.")
      }
      .foreach(connectionActor ! _)
      .onComplete(materializer){case _ => connectionActor ! Connection.IncomingFlowClosed}
  }
}
