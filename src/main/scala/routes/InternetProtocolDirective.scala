package routes

import java.io.IOException

import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import com.typesafe.config.Config
import model.{IpInfo, IpPairSummary, IpPairSummaryRequest}
import spray.json.DefaultJsonProtocol

import scala.concurrent.{ExecutionContextExecutor, Future}

trait Protocols extends DefaultJsonProtocol {
  implicit val ipInfoFormat = jsonFormat5(IpInfo.apply)
  implicit val ipPairSummaryRequestFormat = jsonFormat2(IpPairSummaryRequest.apply)
  implicit val ipPairSummaryFormat = jsonFormat3(IpPairSummary.apply)
}

trait InternetProtocolDirective extends Protocols {
  implicit val system: ActorSystem
  implicit def executor: ExecutionContextExecutor
  implicit val materializer: Materializer
  val config: Config
  val logger: LoggingAdapter

  lazy val ipApiConnectionFlow: Flow[HttpRequest, HttpResponse, Any] =
    Http().outgoingConnection(config.getString("services.ip-api.host"), config.getInt("services.ip-api.port"))


  val ipRoutes = pathPrefix("ip") {
    (get & path(Segment)) { ip =>
      complete {
        fetchIpInfo(ip).map[ToResponseMarshallable] {
          case Right(ipInfo) => ipInfo
          case Left(errorMessage) => BadRequest -> errorMessage
        }
      }
    } ~
      (post & entity(as[IpPairSummaryRequest])) { ipPairSummaryRequest =>
        complete {
          val ip1InfoFuture = fetchIpInfo(ipPairSummaryRequest.ip1)
          val ip2InfoFuture = fetchIpInfo(ipPairSummaryRequest.ip2)
          ip1InfoFuture.zip(ip2InfoFuture).map[ToResponseMarshallable] {
            case (Right(info1), Right(info2)) => IpPairSummary(info1, info2)
            case (Left(errorMessage), _) => BadRequest -> errorMessage
            case (_, Left(errorMessage)) => BadRequest -> errorMessage
          }
        }
      }
  }

  def ipApiRequest(request: HttpRequest): Future[HttpResponse] = Source.single(request).via(ipApiConnectionFlow).runWith(Sink.head)

  def fetchIpInfo(ip: String): Future[Either[String, IpInfo]] = {
    ipApiRequest(RequestBuilding.Get(s"/json/$ip")).flatMap { response =>
      response.status match {
        case OK => Unmarshal(response.entity).to[IpInfo].map(Right(_))
        case BadRequest => Future.successful(Left(s"$ip: incorrect IP format"))
        case _ => Unmarshal(response.entity).to[String].flatMap { entity =>
          val error = s"FreeGeoIP request failed with status code ${response.status} and entity $entity"
          logger.error(error)
          Future.failed(new IOException(error))
        }
      }
    }
  }
}