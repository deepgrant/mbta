package mbta.actor

import akka.actor._
import akka.cluster._
import akka.cluster.ClusterEvent._
import akka.event.Logging
import akka.http.scaladsl.{ConnectionContext,Http}
import akka.http.scaladsl.model.{HttpRequest,HttpMethods,HttpEntity,HttpHeader}
import akka.http.scaladsl.model.headers.{Host,RawHeader}
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.{Authority,NamedHost,Path}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.util.ByteString

import akka.http.scaladsl.server._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives._

import java.io.File
import java.util.concurrent.Semaphore
import java.util.concurrent.{ConcurrentHashMap => MMap}

import scala.concurrent.{Await,Future}
import scala.concurrent.duration._

import spray.json._

object MBTAMain extends App {
  import java.util.concurrent.TimeUnit.{SECONDS => seconds}

  implicit val timeout : akka.util.Timeout = 30.seconds
  implicit val system = ActorSystem()
  implicit val executionContext = system.dispatcher
  implicit val materializer = ActorMaterializer()
  implicit val scheduler = system.scheduler
  implicit val http = Http()
  implicit val logFactory = Logging(system, _ : Class[_ <: Any])

  val log = logFactory(this.getClass)
}
