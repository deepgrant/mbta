package mbta.actor

import akka.actor._
import akka.cluster._
import akka.cluster.ClusterEvent._
import akka.event.Logging
import akka.http.scaladsl.{ConnectionContext,Http}
import akka.http.scaladsl.model.{HttpRequest,HttpResponse,HttpMethods,HttpEntity,HttpHeader}
import akka.http.scaladsl.model.headers.{Host,RawHeader}
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.{Authority,NamedHost,Path}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import akka.stream.ActorMaterializer
import akka.stream.ActorMaterializerSettings

import akka.http.scaladsl.server._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives._

import java.io.File
import java.util.concurrent.Semaphore
import java.util.concurrent.{ConcurrentHashMap => MMap}

import scala.concurrent.{Await,Future}
import scala.concurrent.duration._

import spray.json._

case class fetchVehiclesByRoute(route: String)

object MBTAMain extends App {
  import java.util.concurrent.TimeUnit.{SECONDS => seconds}

  implicit val timeout : akka.util.Timeout = 30.seconds
  implicit val system = ActorSystem()
  implicit val executionContext = system.dispatcher
  implicit val scheduler = system.scheduler
  implicit val logFactory = Logging(system, _ : Class[_ <: Any])

  val log = logFactory(this.getClass)

  val mbtaService = system.actorOf(Props[MBTAService], name="mbtaService")

  import akka.pattern.ask

  val v = mbtaService ? new fetchVehiclesByRoute(route = "CR-Fitchburg")
  Await.result(v, Duration.Inf)

  v.map {
    case vehs => println(vehs)
  }
}

class MBTAService extends Actor with ActorLogging {
  import akka.pattern.ask
  import context.dispatcher
  import akka.pattern.pipe
  import context.dispatcher

  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()

  val api_key = "wX9NwuHnZU2ToO7GmGR9uw"

  def fetchVehiclesPerRoute(route: String): Future[HttpResponse] = {
    Http().singleRequest(HttpRequest(uri = s"https://realtime.mbta.com/developer/api/v2/vehiclesbyroute?api_key=${api_key}&format=json&route=${route}"))
  }

  def receive = {
    case fetchVehiclesByRoute(route) => {
      val dst = sender()
      val vehs_resp = fetchVehiclesPerRoute(route)
      vehs_resp.map {
        case resp => {
          resp.entity.toStrict(5.seconds).map {
            case dec =>
              dec.data.decodeString("UTF-8")
          }.map {
            case s => {
              import DefaultJsonProtocol._
              dst ! (s.toJson)
            }
          }
        }
      }
    }

    case event =>
      log.debug("event={}", event.toString)
  }
}
