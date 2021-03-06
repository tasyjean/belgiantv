package services.akka

import play.api.Logger
import akka.actor.{Props, Actor}
import akka.routing.RoundRobinRouter
import collection.mutable.ListBuffer
import services.HumoReader
import services.ImdbApiService
import controllers.Application
import services.TomatoesApiService
import models.{Channel, Movie, Broadcast}
import services.YeloReader
import java.util.TimeZone
import org.joda.time.DateTimeZone
import services.TmdbApiService
import services.BelgacomReader



import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import scala.concurrent.duration._


import scala.util.{Success, Failure}


class BelgianTvActor extends Actor {
  
  val logger = Logger("application.actor")
  
  override def preRestart(reason: Throwable, message: Option[Any]) {
    logger.error("Restarting due to [{}] when processing [{}]".format(reason.getMessage, message.getOrElse("")), reason)
  }

  def receive: Receive = {

    case msg: LinkTmdb => {
      logger.info("[" + this + "] - Received [" + msg + "] from " + sender)

      val tmdbmovie = TmdbApiService.find(msg.broadcast.name, msg.broadcast.year)

      tmdbmovie.map { mOption =>
        mOption.map{ m =>
           Broadcast.setTmdb(msg.broadcast, m.id.toString)
           m.posterUrl.map(Broadcast.setTmdbImg(msg.broadcast, _))
        }.getOrElse{
        	logger.warn("No TMDb movie found for %s (%s)".format(msg.broadcast.name, msg.broadcast.year))
        	None
        }      
      }
    }

    case msg: LinkImdb => {
      logger.info("[" + this + "] - Received [" + msg + "] from " + sender)

      Movie.find(msg.broadcast.name, msg.broadcast.year).onComplete{
        case Failure(e) => logger.error("Failed to find imdb: " + e.getMessage, e)
        case Success(movie) =>
          val movie2 = movie.orElse {
            val movie = Await.result(ImdbApiService.find(msg.broadcast.name, msg.broadcast.year), 30 seconds)

            movie.map { m =>
              val dbMovie = new Movie(null, m.title, m.id, m.rating, m.year, m.poster)
              val created = Movie.create(dbMovie)
              created
            }
          }

          movie2 match{
            case Some(m) => Broadcast.setImdb(msg.broadcast, m.imdbId)
            case None => logger.warn("No IMDB movie found for %s (%s)".format(msg.broadcast.name, msg.broadcast.year))
          }
      }
    }

    case msg: LinkTomatoes => {
      logger.info(s"[$this] - Received [$msg] from $sender")
      val movie = TomatoesApiService.find(msg.broadcast.name, msg.broadcast.year)
      movie.map { mOption =>
        mOption.map{ m =>
           Broadcast.setTomatoes(msg.broadcast, m.id.toString)
        }.getOrElse{
        	logger.warn("No Tomatoes movie found for %s (%s)".format(msg.broadcast.name, msg.broadcast.year))
        	None
        }      
      }
    }

    case msg: FetchHumo => {
      logger.info("[" + this + "] - Received [" + msg + "] from " + sender)

      HumoReader.fetchDay(msg.day, Channel.channelFilter).onComplete { maybeHumoEvents =>
        maybeHumoEvents match {
          case Failure(e) => logger.error("Failed to read humo day: " + e.getMessage, e)
          case Success(humoEvents) =>

            val broadcasts:Future[List[Broadcast]] = Future.traverse(humoEvents){ event =>
              val broadcast = new Broadcast(
                None,
                event.title,
                event.channel.toLowerCase,
                event.toDateTime,
                event.year,
                humoId = Some(event.id),
                humoUrl = Some(event.url))
              Broadcast.findByDateTimeAndChannel(broadcast.datetime, broadcast.channel).map{ broadcastOption =>
                broadcastOption match {
                  case Some(broadcast) =>
                    if (broadcast.imdbId.isEmpty) {
                      self ! LinkImdb(broadcast)
                    }
                    broadcast
                  case None =>
                    val saved = Broadcast.create(broadcast)
                    self ! LinkImdb(saved)
                    self ! LinkTmdb(saved)
                    self ! LinkTomatoes(saved)
                    saved
                }
              }
            }

            broadcasts.onComplete{ maybeBroadcasts =>
              maybeBroadcasts match{
                case Failure(e) => logger.error("Failed to find broadcasts for humo events: " + e.getMessage, e)
                case Success(broadcasts) =>
                  self ! FetchYelo(msg.day, broadcasts)
                  self ! FetchBelgacom(msg.day, broadcasts)
              }
            }
        }

      }
    }

    case msg: FetchYelo => {
      logger.info("[" + this + "] - Received [" + msg + "] from " + sender)

      YeloReader.fetchDay(msg.day, Channel.channelFilter).onComplete { maybeMovies =>

        maybeMovies match {
          case Failure(e) => logger.error(s"Failed to read yelo day " + msg.day + " - " + e.getMessage, e)
          case Success(movies) =>

            // all unique channels
            // println(movies.groupBy{_.channel}.map{_._1})

            msg.events.foreach { broadcast =>
              if (broadcast.yeloUrl.isEmpty) {
                val found = movies.filter(yeloMovie =>  Channel.unify(yeloMovie.channel).toLowerCase == broadcast.channel.toLowerCase && yeloMovie.toDateTime.withZone(DateTimeZone.UTC) == broadcast.datetime.withZone(DateTimeZone.UTC)).headOption
                found.map { event =>
                  Broadcast.setYelo(broadcast, event.id.toString, event.url)
                }.getOrElse {
                  logger.warn("No yelo match for " + broadcast.channel + " " + broadcast.humanDate + " " + broadcast.name)
                }
              }
            }
        }
      }
    }
    
    case msg: FetchBelgacom => {
      logger.info("[" + this + "] - Received [" + msg + "] from " + sender)
      
      BelgacomReader.readMovies(msg.day).onComplete{ maybeMovies =>

        maybeMovies match {
          case Failure(e) => logger.error("Failed to read belgacom day: " + e.getMessage, e)
          case Success(movies) =>
            msg.events.foreach { broadcast =>
              if (broadcast.belgacomUrl.isEmpty) {
                val found = movies.filter{belgacomMovie =>
                  Channel.unify(belgacomMovie.channelName).toLowerCase == broadcast.channel.toLowerCase && belgacomMovie.toDateTime.withZone(DateTimeZone.UTC) == broadcast.datetime.withZone(DateTimeZone.UTC)
                }.headOption
                found.map { event =>
                  Broadcast.setBelgacom(broadcast, event.programId.toString, event.getProgramUrl)
                }.getOrElse {
                  logger.warn("No belgacom match for " + broadcast.channel + " " + broadcast.humanDate + " " + broadcast.name)
                }
              }
            }
        }
      }
    }

    case x => {
      logger.warn(s"[$this] - Received unknown message [$x] from $sender")
    }
  }
}



