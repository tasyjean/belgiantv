package services

import org.joda.time.DateMidnight
import org.joda.time.format.DateTimeFormat
import org.joda.time.Interval
import org.joda.time.LocalTime
import play.api.libs.json.JsValue
import play.api.libs.json.JsObject
import play.api.Logger
import org.jsoup.Jsoup
import scala.collection.JavaConversions._
import play.api.libs.ws.WS
import java.text.ParseException
import org.joda.time.DateTime

import scala.concurrent.ExecutionContext.Implicits.global
import models.Channel


object YeloReader {
  
  private val logger = Logger("application.yelo")
  
  private val dateFormat = DateTimeFormat.forPattern("dd-MM-YYYY")
  
  def fetchDay(day: DateMidnight, channelFilter: List[String] = List()) = {
    val urls = urlsForDay(day)
    logger.info("Fetching " + urls)
    val first = WS.url(urls(0)).get().map { response =>
      parseDay(day, response.json).filter(result => channelFilter.isEmpty || channelFilter.map(_.toLowerCase).contains(Channel.unify(result.channel).toLowerCase))
    }
    val second = WS.url(urls(1)).get().map { response =>
      parseDay(day, response.json).filter(result => channelFilter.isEmpty || channelFilter.map(_.toLowerCase).contains(Channel.unify(result.channel).toLowerCase))
    }
    for( f <- first; s <- second ) yield ( f ++ s )
  }
  
  private def urlsForDay(day: DateMidnight) = {
    List(
      "http://yelo.be/detail/tvgids?date="+dateFormat.print(day),
      "http://yelo.be/detail/tvgids?date="+dateFormat.print(day) + "&group=anderstalig"
    )
  }
  
  private def parseDay(day: DateMidnight, body: JsValue): Seq[YeloEvent] = {

    val channelMap = parseChannels(body)

    parseEvents(body, channelMap)

  }


  def parseEvents(body: JsValue, channelMap: Map[String, String]): Seq[YeloReader.YeloEvent] = {
    val events = (body \ "Result" \ "Events").as[String]
    val doc = Jsoup.parse(events)

    val pvrbroadcasts = (body \ "Result" \ "Meta" \ "pvrbroadcasts").as[JsObject]

    //println("bc = " + pvrbroadcasts)

    pvrbroadcasts.fields.map {
      case (eventIdentification, eventObject) =>
        val eventId = eventIdentification.toInt
        val title = (eventObject \ "title").as[String]
        val channelWebpvrIid = (eventObject \ "channel_webpvr_id").as[String]
        val startTime = (eventObject \ "start_time").as[String].toLong * 1000
        val endTime = (eventObject \ "end_time").as[String].toLong * 1000

        val eventDiv = Option(doc.getElementsByAttributeValue("id", "js-event-" + eventId).first())
        eventDiv.map {
          div =>
            val url = "http://yelo.be" + div.getElementsByTag("a").attr("href")
            val channel = channelMap.get(channelWebpvrIid).getOrElse("UNKNOWN")
            YeloEvent(eventId, title, url, channel, new Interval(startTime, endTime))
        }.getOrElse {
          logger.warn("No div found for %s %s at %s".format(eventId, title, new DateTime(startTime)))
          YeloEvent(eventId, title, null, "UNKNOWN", new Interval(startTime, endTime))
        }

    }
  }

  private def parseChannels(body: JsValue) = {
    val channels = (body \ "Result" \ "Channels").as[String]
    val channelsDoc = Jsoup.parse(channels)

    val imgs = channelsDoc.getElementsByTag("img")
    val imglist = imgs.subList(0, imgs.size).toList
    val channelMap = imglist.map {
      img =>
        val alt = img.attr("alt")
        val src = img.attr("src")
        val file = src.substring(src.lastIndexOf("/") + 1)
        val code = file.substring(0, 5)
        (code, alt)
    }.toMap
    channelMap
  }

  def toHDUrl(url: String): String = {
    url.replace("vtm", "vtm-hd")
      .replace("canvas", "canvas-hd")
      .replace("een", "een-hd")
      .replace("vier", "vier-hd")
      .replace("vijf", "vijf-hd")
      .replace("2be", "2be-hd")
  }

  case class YeloEvent (
      id:Int, 
      name:String, 
      url: String, 
      channel: String,
      interval: Interval){
    
    def toDateTime() = interval.getStart
  }
}