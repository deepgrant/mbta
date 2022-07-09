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
import akka.NotUsed
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
import akka.stream.scaladsl.{
  Flow,
  Sink,
  Source,
  SourceQueueWithComplete
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
  implicit val logger           = log
  implicit val timeout: Timeout = 30.seconds

  val api_key = sys.env("mbta_api_key")

  val vehiclesLogger = LoggerFactory.getLogger("vehicles")
  val predictionsLogger = LoggerFactory.getLogger("predictions")

  object MBTAaccess {
    def transportSettings = ConnectionPoolSettings(system)
      .withMaxConnections(4)
      .withMaxOpenRequests(256)
      .withPipeliningLimit(64)

    var queue : Option[SourceQueueWithComplete[(HttpRequest, Promise[HttpResponse])]] = None

    def runQ : Future[akka.Done] = {
      val (queue, source) = Source
        .queue[(HttpRequest,Promise[HttpResponse])](bufferSize = 256, overflowStrategy = OverflowStrategy.backpressure)
        .preMaterialize()

      MBTAaccess.queue = Some(queue)

      source
        .throttle(1, 100.millisecond)
        .via(
          Http().newHostConnectionPoolHttps[Promise[HttpResponse]](
            host     = "api-v3.mbta.com",
            port     = 443,
            settings = transportSettings,
            log      = log)
        )
        .map { case (res, p) =>
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
        }
        .runWith(Sink.ignore)
        .andThen {
          case Success(_) =>
            log.error("MBTAaccess.runQ stopped with an unexpected but normal termination.")
          case Failure(t) =>
            log.error(t, "MBTAaccess.runQ stopped")
        }
        .transformWith {
          case _ =>
            log.warning("MBTAaccess.runQ restarting")
            runQ
        }
    }

    def mbtaUri(path: String, query: Option[String] = None) = Uri(
      scheme      = "https",
      path        = Uri.Path(path),
      queryString = query,
      fragment    = None
    )

    def queueRequest(request: HttpRequest) : Future[HttpResponse] = {
      val retVal : Promise[HttpResponse] = Promise()

      MBTAaccess.queue.map { queue =>
        queue.offer((request,retVal)).flatMap(_ => retVal.future).recover {
          case e: Exception => {
            log.error(e, s"MBTAaccess.queueRequest -> ${e}")
            HttpResponse(StatusCodes.InternalServerError)
          }
        }.andThen {
          case Success(response) => log.debug(s"[RESPONSE] MBTAaccess.queueRequest(${request}) -> ${response}")
          case Failure(t) => log.error(s"[RESPONSE] MBTAaccess.queueRequest(${request}) -> ${t}")
        }
      }.getOrElse {
        Future.failed(new Exception("MBTAaccess.queueRequest could not Queue up the request. No Queue found."))
      }
    }

    def parseMbtaResponse(entity: HttpEntity) : Future[Config] = {
      entity
        .withoutSizeLimit
        .dataBytes
        .runWith(Sink.fold(ByteString.empty)(_ ++ _))
        .map { s => ConfigFactory.parseString(s.utf8String) }
        .recover {
          case e: Throwable =>
            log.error("MBTAaccess.parseMbtaResponse -- recover -- {}", e)
            ConfigFactory.empty
        }
    }
  }
  
  override def preStart() : Unit = {
    MBTAaccess.runQ
  }

  object RequestFlow {
    object Request {
      sealed trait T
      case class Routes() extends T
    }

    object Response {
      sealed trait T
      case class Routes(routes: Vector[Config]) extends T
    }

    def runRF = {
      Source
        .tick(initialDelay = FiniteDuration(3, "seconds"), interval = FiniteDuration(10, "seconds"), tick = RequestFlow.Request.Routes)
        .buffer(size = 1, overflowStrategy = OverflowStrategy.dropHead)
        .mapAsync[RequestFlow.Response.Routes](parallelism = 1) { _ =>
          MBTAaccess.queueRequest(
            HttpRequest(uri = MBTAaccess.mbtaUri(
              path  = "/routes",
              query = Some(s"api_key=${api_key}")
            ))
          )
          .flatMap {
            case HttpResponse(StatusCodes.OK, _, entity, _) => {
              MBTAaccess.parseMbtaResponse(entity).map { response =>
                RequestFlow.Response.Routes(
                  routes = response.getObjectList("data").asScala.toVector.filter { _.toConfig.getInt("attributes.type") == 2 }.map { _.toConfig }
                )
              }
            }

            case HttpResponse(code, _, entity, _) => Future.successful {
              log.error("RequestFlow.RoutesFlow.HttpResponse({})", code.toString)
              entity.discardBytes()
              RequestFlow.Response.Routes(routes = Vector.empty[Config])
            }
          }
        }
        .recover {
          case e: Throwable =>
            log.error("RequestFlow.RoutesFlow.recover -- {}", e)
            RequestFlow.Response.Routes(routes = Vector.empty[Config])
        }
        .mapConcat { case RequestFlow.Response.Routes(routes) => routes.map { _.getString("id") } }
        .groupBy(maxSubstreams = 32, f = { case rid => rid })
    }
  }

  self ! MBTAService.Request.trackVehiclesPerRoute()

  def receive = {
    case MBTAService.Request.fetchRoutes() => {
      val dst = sender()
      MBTAaccess.queueRequest(
        HttpRequest(uri = MBTAaccess.mbtaUri(
          path  = "/routes",
          query = Some(s"api_key=${api_key}")
        ))
      ).map {
        case HttpResponse(StatusCodes.OK, _, entity, _) => {
          val resp = MBTAaccess.parseMbtaResponse(entity).map {
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
        route => MBTAaccess.queueRequest(
          HttpRequest(uri = MBTAaccess.mbtaUri(
            path  = "/trips",
            query = Some(s"""filter[route]=${route.getString("id")}&api_key=${api_key}""")
          ))
        ).flatMap {
          case HttpResponse(StatusCodes.OK, _, entity, _) => {
            MBTAaccess.parseMbtaResponse(entity).map { _.getObjectList("data").asScala.toList.map { _.toConfig } }
          }
          case HttpResponse(code, _, entity, _) => {
            log.error(s"Got ${code}")
            MBTAaccess.parseMbtaResponse(entity).map {
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
        route => MBTAaccess.queueRequest(
          HttpRequest(uri = MBTAaccess.mbtaUri(
            path  = "/vehicles",
            query = Some(s"""include=stop&filter[route]=${route.getString("id")}&api_key=${api_key}""")
          ))
        ).flatMap {
          case HttpResponse(StatusCodes.OK, _, entity, _) => {
            MBTAaccess.parseMbtaResponse(entity).map { _.getObjectList("data").asScala.toList.map { _.toConfig } }
          }
          case HttpResponse(code, _, entity, _) => {
            log.error(s"Got ${code}")
            MBTAaccess.parseMbtaResponse(entity).map {
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
        route => MBTAaccess.queueRequest(
          HttpRequest(uri = MBTAaccess.mbtaUri(
            path  = "/predictions",
            query = Some(s"""include=alerts&filter[route]=${route.getString("id")}&api_key=${api_key}""")
          ))
        ).flatMap {
          case HttpResponse(StatusCodes.OK, _, entity, _) => {
            MBTAaccess.parseMbtaResponse(entity).map { _.getObjectList("data").asScala.toList.map { _.toConfig } }
          }
          case HttpResponse(code, _, entity, _) => {
            log.error(s"Got ${code}")
            MBTAaccess.parseMbtaResponse(entity).map {
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
}
