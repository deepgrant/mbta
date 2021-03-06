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
import akka.stream.{
  ActorMaterializer,
  ActorMaterializerSettings,
  OverflowStrategy
}
import akka.stream.contrib.DelayFlow
import akka.stream.contrib.DelayFlow.DelayStrategy
import akka.stream.scaladsl.{
  Flow,
  Sink,
  Source
}
import akka.http.scaladsl.server._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.settings.{
  ClientConnectionSettings,
  ConnectionPoolSettings
}

import collection.JavaConverters._

import com.typesafe.config.{
  Config,
  ConfigFactory,
  ConfigRenderOptions
}

import org.slf4j.{
  LoggerFactory
}

import scala.concurrent.{
  Await,
  ExecutionContext,
  Future,
  Promise
}
import scala.concurrent.duration.{
  Duration,
  FiniteDuration,
  SECONDS
}
import scala.concurrent.duration._
import scala.util.{
  Success,
  Failure,
  Try
}


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

  implicit val system           = ActorSystem()
  implicit val materializer     = ActorMaterializer()
  implicit val logger           = log
  implicit val timeout: Timeout = 30.seconds

  val api_key = sys.env("mbta_api_key")

  val vehiclesLogger = LoggerFactory.getLogger("vehicles")
  val predictionsLogger = LoggerFactory.getLogger("predictions")

  def transportSettings = ConnectionPoolSettings(system)
    .withMaxConnections(4)
    .withMaxOpenRequests(256)
    .withPipeliningLimit(64)

  val queue = Source.queue[(HttpRequest,Promise[HttpResponse])](256, OverflowStrategy.backpressure)
    .throttle(1, 100.millisecond)
    .via(
      Http().newHostConnectionPoolHttps[Promise[HttpResponse]](
        host     = "api-v3.mbta.com",
        port     = 443,
        settings = transportSettings,
        log      = log)
    ).map { case (res, p) =>
      p.tryCompleteWith {
        res.map { case res =>
          res.entity
            .withoutSizeLimit()
            .toStrict(60.seconds)
            .map(res.withEntity(_))
        }.recover { case t =>
          log.error(s"queue recover ${t}")
          Future.failed(t)
        }.getOrElse {
          Future.failed(new IllegalStateException)
        }
      }
    }.watchTermination() { case (mat,done) =>
        done.onComplete {
          case Success(_) => log.info(s"Socket connection gracefully terminated")
          case Failure(t) => log.error(s"Socket connection terminated -> ${t}")
        }
        mat
    }.to(Sink.ignore).run()

  def mbtaUri(path: String, query: Option[String] = None) = Uri(
    scheme      = "https",
    path        = Uri.Path(path),
    queryString = query,
    fragment    = None
  )

  def queueRequest(request: HttpRequest) : Future[HttpResponse] = {
    val retVal : Promise[HttpResponse] = Promise()

    queue.offer((request,retVal)).flatMap(_ => retVal.future).recover {
      case e: Exception => {
        log.error(e, s"queueRequest -> ${e}")
        HttpResponse(StatusCodes.InternalServerError)
      }
    }.andThen {
      case Success(response) => log.debug(s"[RESPONSE] queueRequest(${request}) -> ${response}")
      case Failure(t) => log.error(s"[RESPONSE] queueRequest(${request}) -> ${t}")
    }
  }

  self ! MBTAService.Request.trackVehiclesPerRoute()

  def receive = {
    case MBTAService.Request.fetchRoutes() => {
      val dst = sender()
      queueRequest(
        HttpRequest(uri = mbtaUri(
          path  = "/routes",
          query = Some(s"api_key=${api_key}")
        ))
      ).map {
        case HttpResponse(StatusCodes.OK, _, entity, _) => {
          val resp = MBTAService.parseMbtaResponse(entity).map {
            cf =>
            dst ! MBTAService.Response.fetchRoutes {
              cf.getObjectList("data").asScala.toList.filter { _.toConfig.getInt("attributes.type") == 2 }.map { _.toConfig }
            }
          }
        }
        case HttpResponse(code, _, entity, _) => {
          log.error(s"Got ${code}")
          entity.discardBytes()
        }
      }
    }

    case MBTAService.Request.fetchTripsPerRoute(routes) => {
      val dst = sender()
      Future.sequence { routes.map {
        route => queueRequest(
          HttpRequest(uri = mbtaUri(
            path  = "/trips",
            query = Some(s"""filter[route]=${route.getString("id")}&api_key=${api_key}""")
          ))
        ).flatMap {
          case HttpResponse(StatusCodes.OK, _, entity, _) => {
            MBTAService.parseMbtaResponse(entity).map { _.getObjectList("data").asScala.toList.map { _.toConfig } }
          }
          case HttpResponse(code, _, entity, _) => {
            log.error(s"Got ${code}")
            MBTAService.parseMbtaResponse(entity).map {
              e => log.error(e.root().render(ConfigRenderOptions.concise().setJson(true).setFormatted(true)))
              List(ConfigFactory.empty)
            }
          }
        }
      } }.onComplete {
        case Success(ts) => dst ! ts.flatten
        case Failure(_) => dst ! List.empty[Config]
      }
    }

    case MBTAService.Request.fetchVehiclesPerRoute(routes) => {
      val dst = sender()
      Future.sequence { routes.map {
        route => queueRequest(
          HttpRequest(uri = mbtaUri(
            path  = "/vehicles",
            query = Some(s"""include=stop&filter[route]=${route.getString("id")}&api_key=${api_key}""")
          ))
        ).flatMap {
          case HttpResponse(StatusCodes.OK, _, entity, _) => {
            MBTAService.parseMbtaResponse(entity).map { _.getObjectList("data").asScala.toList.map { _.toConfig } }
          }
          case HttpResponse(code, _, entity, _) => {
            log.error(s"Got ${code}")
            MBTAService.parseMbtaResponse(entity).map {
              e =>
              log.error(e.root().render(ConfigRenderOptions.concise().setJson(true).setFormatted(true)))
              List(ConfigFactory.empty)
            }
          }
        }
      } }.onComplete {
        case Success(vs) => dst ! vs.flatten
        case Failure(_) => dst ! List.empty[Config]
      }
    }

    case MBTAService.Request.trackPredictionsPerRoute(routes) => {
      val dst = sender()
      Future.sequence { routes.map {
        route => queueRequest(
          HttpRequest(uri = mbtaUri(
            path  = "/predictions",
            query = Some(s"""include=alerts&filter[route]=${route.getString("id")}&api_key=${api_key}""")
          ))
        ).flatMap {
          case HttpResponse(StatusCodes.OK, _, entity, _) => {
            MBTAService.parseMbtaResponse(entity).map { _.getObjectList("data").asScala.toList.map { _.toConfig } }
          }
          case HttpResponse(code, _, entity, _) => {
            log.error(s"Got ${code}")
            MBTAService.parseMbtaResponse(entity).map {
              e =>
              log.error(e.root().render(ConfigRenderOptions.concise().setJson(true).setFormatted(true)))
              List(ConfigFactory.empty)
            }
          }
        }
      } }.onComplete {
        case Success(tps) => dst ! tps.flatten
        case Failure(_) => dst ! List.empty[Config]
      }
    }

    case MBTAService.Request.trackVehiclesPerRoute() => {
      (self ? MBTAService.Request.fetchRoutes()).mapTo[MBTAService.Response.fetchRoutes].map {
        case MBTAService.Response.fetchRoutes(routes) => {

          for {
            vs <- (self ? MBTAService.Request.fetchVehiclesPerRoute(routes)).mapTo[List[Config]]
            ps <- (self ? MBTAService.Request.trackPredictionsPerRoute(routes)).mapTo[List[Config]]
          } yield {
            ps.foreach { p => predictionsLogger.info(p.root().render(ConfigRenderOptions.concise.setJson(true))) }
            vs.foreach { v => vehiclesLogger.info(v.root().render(ConfigRenderOptions.concise.setJson(true))) }
            system.scheduler.scheduleOnce(10.seconds, self, MBTAService.Request.trackVehiclesPerRoute())
          }
        }
      }
    }

    case event =>
      log.debug("event={}", event.toString)
  }
}

object MBTAService {
  object Request {
    sealed trait T
    case class fetchRoutes() extends T
    case class fetchTripsPerRoute(routes: List[Config]) extends T
    case class fetchVehiclesPerRoute(routes: List[Config]) extends T
    case class trackVehiclesPerRoute() extends T
    case class trackPredictionsPerRoute(routes: List[Config]) extends T
  }

  object Response {
    sealed trait T
    case class fetchRoutes(routes: List[Config]) extends T
  }

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
