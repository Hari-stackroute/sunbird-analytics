package controllers

import akka.util.Timeout
import akka.util.Timeout.durationToTimeout
import com.typesafe.config.Config
import org.ekstep.analytics.api.util.{APILogger, CacheUtil}
import play.api.Configuration
import play.api.mvc._

import scala.concurrent.duration.DurationInt

/**
 * @author mahesh
 */

class BaseController(cc: ControllerComponents, configuration: Configuration) extends AbstractController(cc) {

  implicit val className = "controllers.BaseController"

  implicit lazy val config: Config = configuration.underlying

  implicit val timeout: Timeout = 20 seconds

    def result(code: String, res: String): Result = {
        val resultObj = code match {
            case "OK" =>
                Ok(res)
            case "CLIENT_ERROR" =>
                BadRequest(res)
            case "SERVER_ERROR" =>
                InternalServerError(res)
            case "REQUEST_TIMEOUT" =>
                RequestTimeout(res)
            case "RESOURCE_NOT_FOUND" =>
                NotFound(res)
            case "FORBIDDEN" =>
                Forbidden(res)
        }
        resultObj.withHeaders(CONTENT_TYPE -> "application/json")
    }

    def authorizeDataExhaustRequest(consumerId: String, channelId: String): Boolean = {
        APILogger.log(s"Authorizing $consumerId and $channelId")
        val status = Option(CacheUtil.getConsumerChannlTable().get(consumerId, channelId))
        if (status.getOrElse(0) == 1) true else false
    }

  def authorizeDataExhaustRequest(request: Request[AnyContent] ): Boolean = {
    val authorizationCheck = config.getBoolean("dataexhaust.authorization_check")
    if(!authorizationCheck) return true

    val consumerId = request.headers.get("X-Consumer-ID").getOrElse("")
    val channelId = request.headers.get("X-Channel-ID").getOrElse("")
    authorizeDataExhaustRequest(consumerId, channelId)
  }
}