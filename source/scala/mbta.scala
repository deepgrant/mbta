package mbta.actor

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.apache.commons.io.IOUtils
import org.apache.pekko
import spray.json._

import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import pekko.actor._
import pekko.Done
import pekko.event.Logging
import pekko.http.scaladsl.Http
import pekko.http.scaladsl.model.{
  HttpRequest,
  HttpResponse,
  HttpEntity,
  StatusCodes
}
import pekko.http.scaladsl.model.Uri
import pekko.NotUsed
import pekko.util.{
  ByteString,
  Timeout
}
import pekko.stream.OverflowStrategy
import pekko.stream.scaladsl.{
  Keep,
  Flow,
  Sink,
  Source,
  SourceQueueWithComplete
}
import pekko.http.scaladsl.settings.ConnectionPoolSettings
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.stream.connectors.s3.S3Settings

object MBTAMain extends App {

  implicit val timeout : pekko.util.Timeout                                 = 10.seconds
  implicit val system  : pekko.actor.ActorSystem                            = ActorSystem()
  implicit val executionContext : scala.concurrent.ExecutionContextExecutor = system.dispatcher
  implicit val scheduler : pekko.actor.Scheduler                            = system.scheduler

  val logFactory: Class[_] => LoggingAdapter = Logging(system, _ : Class[_ <: Any])
  val log: LoggingAdapter = logFactory(this.getClass)

  val mbtaService: ActorRef = system.actorOf(Props[MBTAService](), name="mbtaService")
}

class MBTAService extends Actor with ActorLogging {
  import context.dispatcher

  implicit val system  : pekko.actor.ActorSystem    = ActorSystem()
  implicit val logger  : pekko.event.LoggingAdapter = log
  implicit val timeout : Timeout                    = 30.seconds

  object Config {
    lazy val config: Try[Config] = Try {
      ConfigFactory.parseString(
        sys.env.get("MBTA_CONFIG").getOrElse {
          val resource = getClass.getClassLoader.getResourceAsStream("MBTA.conf")
          val source   = IOUtils.toString(resource, java.nio.charset.Charset.forName("UTF8"))
          resource.close
          source
        }
      )
    }.recover {
      case e: Throwable =>
        log.warning("MBTAService.Config.config -- was not processed successfully -- {}", e)
        ConfigFactory.empty
    }

    def ApiKey : Try[String] = {
      config.flatMap {
        config => {
          Try {
            config.getString("mbta.api")
          }.recoverWith {
            case _ => Try {
              sys.env("MBTA_API_KEY")
            }
          }
        }
      }
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
      }.getOrElse("us-east-1")
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

    def maxRequestsPerPeriod : Int = {
      ApiKey.map { _ => 1000 }.getOrElse(10)
    }

    def maxRequestsWindow : FiniteDuration = 1.minute

    def updatePeriod : FiniteDuration = {
      ApiKey.map { _ => 15.seconds }.getOrElse(10.minutes)
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
    import org.apache.pekko.stream.connectors.s3.{
      AccessStyle,
      MultipartUploadResult,
      S3Attributes,
      S3Ext
    }
    import org.apache.pekko.stream.connectors.s3.scaladsl.{
      S3
    }

    lazy val s3Settings: S3Settings = S3Ext(system)
      .settings
      .withCredentialsProvider(Credentials.operationalCredentials)
      .withAccessStyle(AccessStyle.PathAccessStyle)

    def s3Sink(bucket: String, bucketKey: String) : Sink[ByteString, Future[MultipartUploadResult]] = {
      S3
        .multipartUpload(bucket, bucketKey)
        .withAttributes(S3Attributes.settings(s3Settings))
    }

    def putObject(vj: ByteString, bucket: String, bucketKey: String): Future[Object] = {
      Source.single(vj).runWith(s3Sink(bucket, bucketKey))
        .recover {
          case e: Throwable =>
            log.error("S3Access.putObject -- recoverWith -- {}", e)
            e
        }
    }
  }

  object MBTAaccess {
    def transportSettings: ConnectionPoolSettings = ConnectionPoolSettings(system)
      .withMaxConnections(4)
      .withMaxOpenRequests(256)
      .withPipeliningLimit(64)

    var queue : Option[SourceQueueWithComplete[(HttpRequest, Promise[HttpResponse])]] = None

    def runQ : Future[pekko.Done] = {
      val (queue, source) = Source
        .queue[(HttpRequest,Promise[HttpResponse])](bufferSize = 256, overflowStrategy = OverflowStrategy.backpressure)
        .preMaterialize()

      MBTAaccess.queue = Some(queue)

      source
        .throttle(Config.maxRequestsPerPeriod, Config.maxRequestsWindow)
        .via(
          Http().newHostConnectionPoolHttps[Promise[HttpResponse]](
            host     = "api-v3.mbta.com",
            port     = 443,
            settings = transportSettings,
            log      = log)
        )
        .map { case (res, p) =>
          p.completeWith {
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

    def mbtaQuery(query: Map[String, String] = Map.empty[String, String]) : Option[String] = {
      Config.ApiKey.map { api_key =>
        Uri.Query(query + ("api_key" -> api_key)).toString
      }.orElse {
        Try(Uri.Query(query).toString)
      }.toOption
    }

    def mbtaUri(path: String, query: Option[String] = None): Uri = Uri(
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
    Config.config.map { config =>
      log.info(MBTAService.pp(config))
    }
    MBTAaccess.runQ
  }

  object RequestFlow {
    sealed trait rd
    case class TickRoutes() extends rd
    case class Routes(routes: Vector[Config]) extends rd

    sealed trait vd
    case class FetchRoutes() extends vd
    case class VehicleRoute(
      route            : String,
      directionNames   : Vector[String],
      destinationNames : Vector[String],
    ) extends vd
    case class VehiclesPerRouteRaw(
      route            : VehicleRoute,
      rawVehicles      : Vector[Config]
    ) extends vd
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
      timeStamp           : Long           = java.time.Instant.now().toEpochMilli(),
      direction           : Option[String] = None,
      destination         : Option[String] = None
    ) extends vd
    case class VehicleDataAsJsonString(routeId: String, j: ByteString) extends vd
    case class VehicleDataNull() extends vd

    def vehiclesPerRouteRawFlow : Flow[vd, vd, NotUsed] = {
      Flow[vd]
        .mapAsync(parallelism = 12) {
          case vr @ VehicleRoute(route, _, _) => {
            MBTAaccess.queueRequest(
              HttpRequest(uri = MBTAaccess.mbtaUri(
                path  = "/vehicles",
                query = MBTAaccess.mbtaQuery(Map("include" -> "stop", "filter[route]" -> route))
              ))
            ).flatMap {
              case HttpResponse(StatusCodes.OK, _, entity, _) => {
                MBTAaccess.parseMbtaResponse(entity).map { resp =>
                  log.info("vehiclesPerRouteRawFlow({}) returned: OK", route)
                  VehiclesPerRouteRaw(
                    route       = vr,
                    rawVehicles = resp.getObjectList("data").asScala.toVector.map { _.toConfig }
                  )
                }
              }
              case HttpResponse(code, _, entity, _) => Future.successful {
                log.error("vehiclesPerRouteFlow returned unexpected code: {}", code.toString)
                entity.discardBytes()
                VehiclesPerRouteRaw(
                  route       = vr,
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
              val directionId : Try[Int] = Try(r.getInt("attributes.direction_id"))

              VehicleData(
                routeId             = route.route,
                vehicleId           = Try(r.getString("attributes.label")).toOption,
                stopId              = Try(r.getString("relationships.stop.data.id")).toOption,
                tripId              = Try(r.getString("relationships.trip.data.id")).toOption,
                bearing             = Try(r.getInt("attributes.bearing")).toOption,
                directionId         = directionId.toOption,
                currentStatus       = Try(r.getString("attributes.current_status")).toOption,
                currentStopSequence = Try(r.getInt("attributes.current_stop_sequence")).toOption,
                latitude            = Try(r.getDouble("attributes.latitude")).toOption,
                longitude           = Try(r.getDouble("attributes.longitude")).toOption,
                speed               = Try(r.getDouble("attributes.speed")).toOption,
                updatedAt           = Try(r.getString("attributes.updated_at")).toOption,
                direction           = directionId.flatMap { id => Try(route.directionNames(id)) }.toOption,
                destination         = directionId.flatMap { id => Try(route.destinationNames(id)) }.toOption
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
                query = MBTAaccess.mbtaQuery()
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

            implicit object VehicleDataFormat extends JsonFormat[VehicleData] {
              val baseFormat = jsonFormat18(VehicleData.apply)

              override def read(json : JsValue) : VehicleData = ???
              override def write(v : VehicleData) : JsValue = {
                val base : JsValue = baseFormat.write(v)
                v.latitude.flatMap { lat =>
                  v.longitude.map { lon =>
                    JsObject(base.asJsObject.fields ++ Map("position" -> JsString(s"${lat}, ${lon}"))) : JsValue
                  }
                }.getOrElse(JsObject(base.asJsObject.fields) : JsValue)
              }
            }

            VehicleDataAsJsonString(v.routeId, ByteString(v.toJson.toString + "\n", "UTF-8"))

          case _ => VehicleDataNull()
        }
        .fold(VehicleDataAsJsonString("", ByteString.empty)) {
          case (acc, v: VehicleDataAsJsonString) => VehicleDataAsJsonString(v.routeId, acc.j ++ v.j)
          case (acc, _) => acc
        }
    }

    def pushToS3 : Flow[vd, vd, NotUsed] = {
      Flow[vd]
        .mapAsync(parallelism = 1) {
          case vj @ VehicleDataAsJsonString("", ByteString.empty) => Future.successful {
            log.debug("Supressing null s3 write.")
            vj
          }
          case vj : VehicleDataAsJsonString  => {
            Config.getStorageBucket.flatMap { bucket =>
              Config.getStorageBucketPrefix.map { prefix =>
                S3Access.putObject(vj.j, bucket, s"${prefix}/${vj.routeId}/${java.time.Instant.now().toEpochMilli()}.json").map { _ => vj }
              }
            }
          }.getOrElse(Future.successful {
            VehicleDataAsJsonString("", ByteString.empty)
          })
          case _ => Future.successful {
            VehicleDataAsJsonString("", ByteString.empty)
          }
        }
    }

    def fetchRoutes : Flow[vd, vd, NotUsed] = {
      Flow[vd]
        .mapAsync[Routes](parallelism = 1) { _ =>
          MBTAaccess.queueRequest(
            //
            // Filter on just the Commuter rail, Rapid Transit. The Bus routes push to too many substreams.
            //
            HttpRequest(uri = MBTAaccess.mbtaUri(
              path  = "/routes",
              query = MBTAaccess.mbtaQuery(Map("filter[type]" -> "0,1,2"))
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
        .mapConcat { case Routes(routes) =>
          routes.map { r =>
            VehicleRoute(
              route            = r.getString("id"),
              directionNames   = Try(r.getStringList("attributes.direction_names").asScala.toVector).getOrElse(Vector.empty[String]),
              destinationNames = Try(r.getStringList("attributes.direction_destinations").asScala.toVector).getOrElse(Vector.empty[String])
            )
          }
        }
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
        .via(pushToS3)
        .mergeSubstreams
        .toMat(Sink.foreach {
          case VehicleDataAsJsonString(_, x) => log.info(x.utf8String)
          case _ =>
        })(Keep.right)
        .run()
    }

    def runRF: Future[Done] = {
      Source
        .tick(initialDelay = FiniteDuration(1, "seconds"), interval = Config.updatePeriod, tick = TickRoutes)
        .buffer(size = 1, overflowStrategy = OverflowStrategy.dropHead)
        .mapAsync[Done](parallelism = 1) { _ =>
          runFetchFlows
        }
        .toMat(Sink.ignore)(Keep.right)
        .run()
    }
  }

  RequestFlow.runRF

  def receive: PartialFunction[Any,Unit] = {
    case event =>
      log.error("Unexpected event={}", event.toString)
  }
}

object MBTAService {
  def pp(x: Any): String = pprint.PPrinter.Color.tokenize(x, width = 512, height = 1000).mkString

  object Request {
    sealed trait T
  }

  object Response {
    sealed trait T
  }
}
