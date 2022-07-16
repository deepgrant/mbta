package mbta.actor

import akka.actor._
import akka.cluster._
import akka.cluster.ClusterEvent._
import akka.Done
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
import akka.http.scaladsl.model.headers.{
  Host,
  RawHeader
}
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.{
  Authority,
  NamedHost,
  Path
}
import akka.NotUsed
import akka.stream.ActorMaterializer
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
  Keep,
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

import org.apache.commons.io.IOUtils

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

import spray.json._

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

  object Config {
    lazy val config = Try {
      val resource = getClass.getClassLoader.getResourceAsStream("MBTA.conf")
      val source   = IOUtils.toString(resource, java.nio.charset.Charset.forName("UTF8"))
      resource.close
      ConfigFactory.parseString(source)
    }.recover {
      case e: Throwable =>
        log.warning("MBTAService.Config.config -- was not processed successfully -- {}", e)
        ConfigFactory.empty
    }

    def AccessKey : Try[String] = {
      config.flatMap {
        config => {
          Try {
            config.getString("mbta.aws.credentials.accessKey")
          }.recoverWith {
            case _ => Try {
              sys.env("AWS_ACCESS_KEY_ID")
            }
          }
        }
      }
    }

    def SecretKey : Try[String] = {
      config.flatMap {
        config => {
          Try {
            config.getString("mbta.aws.credentials.secretKey")
          }.recoverWith {
            case _ => Try {
              sys.env("AWS_SECRET_ACCESS_KEY")
            }
          }
        }
      }
    }

    def Region : String = {
      config.flatMap {
        config => {
          Try {
            config.getString("mbta.aws.credentials.region")
          }.recoverWith {
            case _ => Try {
              sys.env("AWS_REGION")
            }
          }
        }
      }.getOrElse("US_EAST_1")
    }

    def S3RoleArn : Try[String] = {
      config.flatMap {
        config => {
          Try {
            config.getString("mbta.aws.credentials.s3AccessRole")
          }.recoverWith {
            case _ => Try {
              sys.env("MBTA_S3_ROLEARN")
            }
          }
        }
      }
    }

    def S3RoleArnExternalId : Try[String] = {
      config.flatMap {
        config => {
          Try {
            config.getString("mbta.aws.credentials.s3AccessRoleExternalId")
          }.recoverWith {
            case _ => Try {
              sys.env("MBTA_S3_ROLEARN_EXTERNAL_ID")
            }
          }
        }
      }
    }

    def getStorageBucket : Try[String] = {
      config.flatMap {
        config => {
          Try {
            config.getString("mbta.aws.s3.bucket")
          }.recoverWith {
            case _ => Try {
              sys.env("MBTA_STORAGE_BUCKET")
            }
          }
        }
      }
    }

    def getStorageBucketPrefix : Try[String] = {
      config.flatMap {
        config => {
          Try {
            config.getString("mbta.aws.s3.prefix")
          }.recoverWith {
            case _ => Try {
              sys.env("MBTA_STORAGE_PREFIX")
            }
          }
        }
      }
    }
  }

  object Credentials {
    import software.amazon.awssdk.auth.credentials.{
      AwsBasicCredentials,
      AwsCredentialsProvider,
      DefaultCredentialsProvider,
      StaticCredentialsProvider
    }
    import software.amazon.awssdk.services.sts.auth.{
      StsAssumeRoleCredentialsProvider
    }
    import software.amazon.awssdk.services.sts.model.AssumeRoleRequest

    private[this] lazy val longTermCredentials : StaticCredentialsProvider = StaticCredentialsProvider.create(
      {
        Config.AccessKey.flatMap { ak => 
          Config.SecretKey.map { sk =>
            AwsBasicCredentials.create(ak, sk)
          }
        }.getOrElse {
          val defaultCredentials = DefaultCredentialsProvider.builder().build().resolveCredentials()
          AwsBasicCredentials.create(defaultCredentials.accessKeyId(), defaultCredentials.secretAccessKey())
        }
      }
    )

    def operationalCredentials : AwsCredentialsProvider = {
      Config.S3RoleArn.map { roleArn =>
        val arr = {
          val arr = AssumeRoleRequest
            .builder()
            .roleArn(roleArn)
            .roleSessionName("MBTA")
            .durationSeconds(300)

            Config.S3RoleArnExternalId.map { externalId =>
              arr.externalId(externalId)
            }.getOrElse(arr).build()
        }

        StsAssumeRoleCredentialsProvider.builder()
          .refreshRequest(arr)
          .build()
      }.getOrElse {
        longTermCredentials
      }
    }
  }

  object S3Access {
    import akka.stream.alpakka.s3.{
      MultipartUploadResult,
      S3Attributes,
      S3Ext,
      S3Settings
    }
    import akka.stream.alpakka.s3.scaladsl.{
      S3
    }

    lazy val s3Settings = S3Ext(system)
      .settings
      .withCredentialsProvider(Credentials.operationalCredentials)

    def s3Sink(bucket: String, bucketKey: String) : Sink[ByteString, Future[MultipartUploadResult]] =
      S3
        .multipartUpload(bucket, bucketKey)
        .withAttributes(S3Attributes.settings(s3Settings))
  }

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
        .throttle(1000, 1.minute)
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
        log.info("MBTAaccess.queueRequest.request: {}", request.toString)

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
    sealed trait rd
    case class TickRoutes() extends rd
    case class Routes(routes: Vector[Config]) extends rd

    sealed trait vd
    case class FetchRoutes() extends vd
    case class VehicleRoute(route: String) extends vd
    case class VehiclesPerRouteRaw(route: String, rawVehicles: Vector[Config]) extends vd
    case class VehicleData(
      routeId             : String,
      vehicleId           : Option[String] = None,
      stopId              : Option[String] = None,
      tripId              : Option[String] = None,
      bearing             : Option[Int]    = None,
      directionId         : Option[Int]    = None,
      currentStatus       : Option[String] = None,
      currentStopSequence : Option[Int]    = None,
      latitude            : Option[Double] = None,
      longitude           : Option[Double] = None,
      speed               : Option[Double] = None,
      updatedAt           : Option[String] = None,
      stopName            : Option[String] = None,
      stopPlatformName    : Option[String] = None,
      stopZone            : Option[String] = None,
      timeStamp           : Long           = java.time.Instant.now().toEpochMilli()
    ) extends vd
    case class VehicleDataAsJsonString(j: ByteString) extends vd
    case class VehicleDataNull() extends vd

    def vehiclesPerRouteRawFlow : Flow[vd, vd, NotUsed] = {
      Flow[vd]
        .mapAsync(parallelism = 12) {
          case VehicleRoute(route) => {
            MBTAaccess.queueRequest(
              HttpRequest(uri = MBTAaccess.mbtaUri(
                path  = "/vehicles",
                query = Some(s"""include=stop&filter[route]=${route}&api_key=${api_key}""")
              ))
            ).flatMap {
              case HttpResponse(StatusCodes.OK, _, entity, _) => {
                MBTAaccess.parseMbtaResponse(entity).map { resp =>
                  log.info("vehiclesPerRouteRawFlow({}) returned: OK", route)
                  VehiclesPerRouteRaw(
                    route       = route,
                    rawVehicles = resp.getObjectList("data").asScala.toVector.map { _.toConfig }
                  )
                }
              }
              case HttpResponse(code, _, entity, _) => Future.successful {
                log.error("vehiclesPerRouteFlow returned unexpected code: {}", code.toString)
                entity.discardBytes()
                VehiclesPerRouteRaw(
                  route       = route,
                  rawVehicles = Vector.empty[Config]
                )
              }
            }
          }
          case unExpected => Future.failed {
            log.error("vehiclesPerRouteRawFlow unexpected input: {}", unExpected.toString)
            new Exception("vehiclesPerRouteRawFlow unexpected input")
          }
        }
    }

    def vehiclesPerRouteFlow : Flow[vd, vd, NotUsed] = {
      Flow[vd]
        .mapConcat {
          case VehiclesPerRouteRaw(route, rv) => {
            rv.map { r =>
              VehicleData(
                routeId             = route,
                vehicleId           = Try(r.getString("attributes.label")).toOption,
                stopId              = Try(r.getString("relationships.stop.data.id")).toOption,
                tripId              = Try(r.getString("relationships.trip.data.id")).toOption,
                bearing             = Try(r.getInt("attributes.bearing")).toOption,
                directionId         = Try(r.getInt("attributes.direction_id")).toOption,
                currentStatus       = Try(r.getString("attributes.current_status")).toOption,
                currentStopSequence = Try(r.getInt("attributes.current_stop_sequence")).toOption,
                latitude            = Try(r.getDouble("attributes.latitude")).toOption,
                longitude           = Try(r.getDouble("attributes.longitude")).toOption,
                speed               = Try(r.getDouble("attributes.speed")).toOption,
                updatedAt           = Try(r.getString("attributes.updated_at")).toOption
              )
            }
          }
          case unExpected => {
            log.error("vehiclesPerRouteFlow unexpected input: {}", unExpected.toString)
            Vector(VehicleDataNull())
          }
        }
    }

    def stopIdLookupFlow : Flow[vd, vd, NotUsed] = {
      Flow[vd]
        .mapAsync(parallelism = 16) {
          case vd : VehicleData => {
            vd.stopId.map { stopId =>
              val uri = MBTAaccess.mbtaUri(
                path  = s"/stops/${stopId}",
                query = Some(s"""api_key=${api_key}""")
              )
              
              MBTAaccess.queueRequest(
                HttpRequest(uri = uri)
              ).flatMap {
                case HttpResponse(StatusCodes.OK, _, entity, _) => {
                  MBTAaccess.parseMbtaResponse(entity).map { r =>
                    vd.copy(
                      stopName         = Try(r.getString("data.attributes.name")).toOption,
                      stopPlatformName = Try(r.getString("data.attributes.platform_name")).toOption,
                      stopZone         = Try(r.getString("data.relationships.zone.data.id")).toOption
                    )
                  }
                }
                case HttpResponse(code, _, entity, _) => Future.successful {
                  log.error("stopIdLookupFlow returned unexpected code: {} with uri: {}", code.toString, uri.toString)
                  entity.discardBytes()
                  vd
                }
              }
            }.getOrElse {
              Future.successful(vd)
            }
          }

          case unExpected => Future.successful {
            log.error("stopIdLookupFlow unexpected input: {}", unExpected.toString)
            VehicleDataNull()
          }
        }
    }

    def renderAsJson : Flow[vd, vd, NotUsed] = {
      Flow[vd]
        .wireTap { v => {
          log.info("renderAsJson -- {}", MBTAService.pp(v))
        }}
        .map { 
          case v: VehicleData =>
            import DefaultJsonProtocol._

            implicit val VehicleDataFormat = jsonFormat16(VehicleData)
            ByteString(v.toJson.toString + s" -- ${java.util.UUID.randomUUID}\n", "UTF-8")
          case _ => ByteString.empty
        }
        .fold(ByteString.empty)( _ ++ _ )
        .map(VehicleDataAsJsonString(_))
        // .to(S3Access.s3Sink("cs-gmills-mbta", s"MBTA/vehicle/positions/${java.time.Instant.now().toEpochMilli()}.json"))
    }

    def fetchRoutes : Flow[vd, vd, NotUsed] = {
      Flow[vd]
        .mapAsync[Routes](parallelism = 1) { _ =>
          MBTAaccess.queueRequest(
            //
            // Filter on just the Commuter rail, Rapid Transit and Ferrys. The Bus routes push to too many substreams.
            //
            HttpRequest(uri = MBTAaccess.mbtaUri(
              path  = "/routes",
              query = Some(s"filter[type]=0,1,2,4&api_key=${api_key}")
            ))
          )
          .flatMap {
            case HttpResponse(StatusCodes.OK, _, entity, _) => {
              MBTAaccess.parseMbtaResponse(entity).map { response =>
                Routes(
                  routes = response.getObjectList("data").asScala.toVector.map { _.toConfig }
                )
              }
            }

            case HttpResponse(code, _, entity, _) => Future.successful {
              log.error("RequestFlow.RoutesFlow.HttpResponse({})", code.toString)
              entity.discardBytes()
              Routes(routes = Vector.empty[Config])
            }
          }
        }
        .recover {
          case e: Throwable =>
            log.error("RequestFlow.RoutesFlow.recover -- {}", e)
            Routes(routes = Vector.empty[Config])
        }
        .mapConcat { case Routes(routes) => routes.map { r => VehicleRoute(route = r.getString("id")) } }
    }

    def runFetchFlows : Future[Done] = {
      Source
        .single(FetchRoutes())
        .via(fetchRoutes)
        .groupBy(maxSubstreams = 128, f = { case rid => rid })
        .via(vehiclesPerRouteRawFlow)
        .via(vehiclesPerRouteFlow)
        .via(stopIdLookupFlow)
        .via(renderAsJson)
        .mergeSubstreams
        .toMat(Sink.foreach { 
          case VehicleDataAsJsonString(x) => log.info(x.utf8String) 
          case _ => 
        })(Keep.right)
        .run()
    }

    def runRF = {
      Source
        .tick(initialDelay = FiniteDuration(1, "seconds"), interval = FiniteDuration(15, "seconds"), tick = TickRoutes)
        .buffer(size = 1, overflowStrategy = OverflowStrategy.dropHead)
        .mapAsync[Done](parallelism = 1) { _ =>
          runFetchFlows
        }
        .toMat(Sink.ignore)(Keep.right)
        .run()
    }
  }

  RequestFlow.runRF

  def receive = {
    case event =>
      log.error("Unexpected event={}", event.toString)
  }
}

object MBTAService {
  def pp(x: Any) = pprint.PPrinter.Color.tokenize(x, width = 512, height = 1000).mkString

  object Request {
    sealed trait T
  }

  object Response {
    sealed trait T
  }
}
