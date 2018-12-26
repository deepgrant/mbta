package mbta.actor

import akka.actor._
import akka.cluster._
import akka.cluster.ClusterEvent._
import akka.event.{
  Logging,
  LoggingAdapter,
  LogSource
}
import akka.http.scaladsl.{ConnectionContext,Http}
import akka.http.scaladsl.model.{
  HttpRequest,
  HttpResponse,
  HttpMethods,
  HttpEntity,
  HttpHeader,
  StatusCodes,
  Uri
}
import akka.http.scaladsl.model.headers.{Host,RawHeader}
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.{Authority,NamedHost,Path}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.util.{
  ByteString,
  Timeout
}
import akka.stream.ActorMaterializer
import akka.stream.ActorMaterializerSettings
import akka.stream.scaladsl.{
  Flow,
  Sink,
  Source
}
import akka.http.scaladsl.server._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives._

import com.typesafe.config.{
  Config,
  ConfigFactory,
  ConfigRenderOptions
}
import java.io.File
import java.util.concurrent.Semaphore
import java.util.concurrent.{ConcurrentHashMap => MMap}

import scala.concurrent.{Await,Future}
import scala.concurrent.duration._
import scala.util.{Success,Failure,Try}


object MBTAMain extends App {
  import java.util.concurrent.TimeUnit.{SECONDS => seconds}

  implicit val timeout : akka.util.Timeout = 10.seconds
  implicit val system = ActorSystem()
  implicit val executionContext = system.dispatcher
  implicit val scheduler = system.scheduler
  implicit val logFactory = Logging(system, _ : Class[_ <: Any])

  val log = logFactory(this.getClass)

  val mbtaService = system.actorOf(Props[MBTAService], name="mbtaService")
}

class MBTAService extends Actor with ActorLogging {
  import akka.pattern.ask
  import context.dispatcher
  import akka.pattern.pipe
  import context.dispatcher

  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()
  implicit val logger = log
  implicit val timeout: Timeout = 30.seconds

  val api_key = sys.env("mbta_api_key")

  self ! MBTAService.fetchRoute(None)

  def receive = {
    case MBTAService.fetchRoute(None) => {
      Http().singleRequest(
        HttpRequest(
          uri = Uri.from(
            scheme = "https",
            host   = "api-v3.mbta.com",
            path   = "/routes",
            queryString  = Some(s"api_key=${api_key}")
          )
        )
      ).map {
        case HttpResponse(StatusCodes.OK, _, entity, _) =>
          val resp = MBTAService.parseMbtaResponse(entity).map {
            cf => log.info(s"Got: ${cf}")
          }
        case HttpResponse(code, _, entity, _) =>
          log.error(s"Got ${code}")
          entity.discardBytes()
      }


    }

    case event =>
      log.debug("event={}", event.toString)
  }
}

object MBTAService {
  case class fetchRoute(route: Option[String] = None)

  def parseMbtaResponse(entity: HttpEntity)(implicit log: LoggingAdapter, system : ActorSystem, context : ActorRefFactory, timeout : Timeout, materializer: ActorMaterializer) : Future[Config] = {
    implicit val executionContext = system.dispatcher

    entity.toStrict(5.seconds).flatMap {
      case entity =>
        entity.dataBytes.runWith(Sink.fold[String,ByteString] { "" } {
          (s: String, bs: ByteString) => s + bs.decodeString("UTF-8")
        }).map {
          case s => ConfigFactory.parseString(s)
        }.recover {
          case e: Exception =>
            ConfigFactory.empty
        }
    }
  }
}
