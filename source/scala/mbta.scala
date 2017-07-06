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
import scala.util.{Success,Failure,Try}

import spray.json._

case class fetchVehiclesByRoute(route: String)

object JsonData {
  case class Vehicle(
    vehicle_id        : String,
    vehicle_timestamp : String,
    vehicle_lat       : String,
    vehicle_lon       : String,
    vehicle_bearing   : String,
    vehicle_label     : String,
    vehicle_speed     : String
  )

  case class Trip(
    trip_name     : String,
    trip_id       : String,
    trip_headsign : String,
    vehicle       : Vehicle
  )

  case class Direction(
    direction_name : String,
    direction_id   : String,
    trip           : List[Trip]
  )

  case class VehiclesByRoute(
    route_type : String,
    route_name : String,
    route_id   : String,
    mode_name  : String,
    direction  : List[Direction]
  )

  case class VehicleByRouteFlat(
    route_id        : String,
    route_name      : String,
    direction_id    : String,
    direction_name  : String,
    vehicle_id      : String,
    trip_id         : String,
    lat             : String,
    lon             : String,
    nearest_stop_id : String
  )

  case class VehiclesByRouteFlat(table: List[VehicleByRouteFlat])

  case class Stop(
    stop_id             : String,
    stop_name           : String,
    parent_station      : String,
    parent_station_name : String,
    stop_lat            : String,
    stop_lon            : String,
    distance            : String
  )

  case class StopsByLocation(stop : List[Stop])

  object MBTAJsonProtocol {
    import DefaultJsonProtocol._

    implicit val VehicleParam             = jsonFormat7(Vehicle)
    implicit val TripParam                = jsonFormat4(Trip)
    implicit val DirectionParam           = jsonFormat3(Direction)
    implicit val VehiclesByRouteParam     = jsonFormat5(VehiclesByRoute)
    implicit val VehicleByRouteFlatParam  = jsonFormat9(VehicleByRouteFlat)
    implicit val VehiclesByRouteFlatParam = jsonFormat1(VehiclesByRouteFlat)
    implicit val StopParam                = jsonFormat7(Stop)
    implicit val StopsByLocationParam     = jsonFormat1(StopsByLocation)
  }
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

  import akka.pattern.ask

  val v = mbtaService ? new fetchVehiclesByRoute(route = "CR-Fitchburg")
  Await.result(v, Duration.Inf)

  v.map {
    case vehs: JsonData.VehiclesByRouteFlat => {
      import DefaultJsonProtocol._
      import JsonData.MBTAJsonProtocol._
      println(vehs.toJson.prettyPrint)
    }
  }
}

class MBTAService extends Actor with ActorLogging {
  import akka.pattern.ask
  import context.dispatcher
  import akka.pattern.pipe
  import context.dispatcher

  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()

  val api_key = sys.env("mbta_api_key")

  def fetchVehiclesPerRoute(route: String): Future[HttpResponse] = {
    Http().singleRequest(HttpRequest(uri = s"https://realtime.mbta.com/developer/api/v2/vehiclesbyroute?api_key=${api_key}&format=json&route=${route}"))
  }

  def fetchStationNearestCoOrds(lat: String, lon: String): Future[HttpResponse] = {
    Http().singleRequest(HttpRequest(uri = s"https://realtime.mbta.com/developer/api/v2/stopsbylocation?api_key=${api_key}&format=json&lat=${lat}&lon=${lon}"))
  }

  import DefaultJsonProtocol._
  import JsonData.MBTAJsonProtocol._

  def decodeVehiclesPerRoute(route: String) : Future[JsonData.VehiclesByRoute] = {
    fetchVehiclesPerRoute(route).flatMap {
      case resp => {
        resp.entity.toStrict(5.seconds).map {
          case dec =>
            dec.data.decodeString("UTF-8")
        }.map {
          case s => {
            s.parseJson.convertTo[JsonData.VehiclesByRoute]
          }
        }
      }
    }
  }

  def decodeNearestStops(lat: String, lon: String) : Future[JsonData.StopsByLocation] = {
    fetchStationNearestCoOrds(lat, lon).flatMap {
      case nearest =>
        nearest.entity.toStrict(5.seconds).map {
          case dec =>
            dec.data.decodeString("UTF-8")
        }.map {
          case rs =>
            rs.parseJson.convertTo[JsonData.StopsByLocation]
        }
    }
  }

  def receive = {
    case fetchVehiclesByRoute(route) => {
      import JsonData._

      val dst = sender()
      val vehs_fut = decodeVehiclesPerRoute(route).map {
        case vehs =>
          {
            for {
              dir <- vehs.direction
              trip <- dir.trip
            } yield {
              decodeNearestStops(trip.vehicle.vehicle_lat, trip.vehicle.vehicle_lon).map {
                case stops => {
                  val stop_name = stops.stop.size match {
                    case 0 => ""
                    case _ => stops.stop.head.stop_name
                  }
                  new VehicleByRouteFlat(
                    vehs.route_id,
                    vehs.route_name,
                    dir.direction_id,
                    dir.direction_name,
                    trip.vehicle.vehicle_id,
                    trip.trip_id,
                    trip.vehicle.vehicle_lat,
                    trip.vehicle.vehicle_lon,
                    stop_name
                  )
                }
              }
            }
          }
      }.flatMap {
        case veh_f => {
          Future.foldLeft[VehicleByRouteFlat, List[VehicleByRouteFlat]](veh_f)(List.empty[VehicleByRouteFlat]) { case (l_veh_f, veh_f) => l_veh_f :+ veh_f }
        }
      }

      vehs_fut.onComplete {
        case result  => {
          val response = result match {
            case Success(result) => new VehiclesByRouteFlat(result)
            case Failure(_) => new VehiclesByRouteFlat(List.empty[VehicleByRouteFlat])
          }

          dst ! response
        }
      }
    }

    case event =>
      log.debug("event={}", event.toString)
  }
}
