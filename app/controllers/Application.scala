package controllers

import java.io.File

import akka.actor.{Actor, Props}
import akka.pattern.ask
import play.api.Logger
import play.api.Play.current
import play.api.libs.concurrent.{Akka, Promise}
import play.api.libs.iteratee.{Concurrent, Enumerator, Iteratee}
import play.api.libs.ws.WS
import play.api.mvc._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Future, SyncVar}
import scala.sys.process.{BasicIO, Process}

object Application extends Controller {

  val actor = Akka.system.actorOf(Props[WebsocketEchoActor])
  val minecraftActor = Akka.system.actorOf(Props[MinecraftActor])

  // http endpoint to check that the server is running
  def index = Action {
    Ok("I'm alive!\n")
  }

  def startMinecraft = Action {
    minecraftActor ! StartMinecraftServer()

    Ok("ok")
  }

  def sendMinecraftCmd = Action {
    minecraftActor ! SendMinecraftCommand("/help")

    Ok("ok")
  }

  // endpoint that opens an echo websocket
  def wsEcho = WebSocket.using[String] {
    request => {
      Logger.info(s"wsEcho, client connected.")
      var channel: Option[Concurrent.Channel[String]] = None
      val outEnumerator: Enumerator[String] = Concurrent.unicast(c => channel = Some(c))

      val inIteratee: Iteratee[String, Unit] = Iteratee.foreach[String](receivedString => {
        // send string back
        Logger.info(s"wsEcho, received: $receivedString")
        channel.foreach(_.push(receivedString))
      })

      (inIteratee, outEnumerator)
    }
  }

  // async version of the echo websocket -- code is exactly the same!
  def wsEchoAsync = WebSocket.async[String] {
    request => Future {
      Logger.info(s"wsEchoAsync, client connected.")
      var channel: Option[Concurrent.Channel[String]] = None
      val outEnumerator: Enumerator[String] = Concurrent.unicast(c => channel = Some(c))

      val inIteratee: Iteratee[String, Unit] = Iteratee.foreach[String](receivedString => {
        // send string back
        Logger.info(s"wsEchoAsync, received: $receivedString")
        channel.foreach(_.push(receivedString))
      })

      (inIteratee, outEnumerator)
    }
  }

  // sends the time every second, ignores any input
  def wsTime = WebSocket.async[String] {
    request => Future {
      Logger.info(s"wsTime, client connected.")

      val outEnumerator: Enumerator[String] = Enumerator.repeatM(Promise.timeout(s"${new java.util.Date()}", 1000))
      val inIteratee: Iteratee[String, Unit] = Iteratee.ignore[String]

      (inIteratee, outEnumerator)
    }
  }

  // sends the time every second, ignores any input
  def wsPingPong = WebSocket.async[String] {
    request => Future {
      Logger.info(s"wsPingPong, client connected.")

      var switch: Boolean = true
      val outEnumerator = Enumerator.repeatM[String](Promise.timeout({
        switch = !switch
        if (switch) "                <----- pong" else "ping ----->"
      }, 1000))

      (Iteratee.ignore[String], outEnumerator)
    }
  }

  // interleaves two enumerators
  def wsInterleave = WebSocket.async[String] {
    request => Future {
      Logger.info("wsInterleave, client connected")
      val en1: Enumerator[String] = Enumerator.repeatM(Promise.timeout("AAAA", 2000))
      val en2: Enumerator[String] = Enumerator.repeatM(Promise.timeout("BBBB", 1500))
      (Iteratee.ignore[String], Enumerator.interleave(en1, en2))
    }
  }

  // sends content from a file
  def wsFromFile = WebSocket.async[Array[Byte]] {
    request => Future {
      Logger.info("wsFromFile, client connected")
      val file: File = new File("test.txt")
      val outEnumerator = Enumerator.fromFile(file)
      (Iteratee.ignore[Array[Byte]], outEnumerator.andThen(Enumerator.eof))
    }
  }

  def wsWithActor = WebSocket.async[String] {
    request => {
      Logger.info("wsWithActor, client connected")

      (actor ? ClientConnected())(3 seconds).mapTo[(Iteratee[String, _], Enumerator[String])].recover {
        case e: TimeoutException =>
          // the actor didn't respond
          Logger.error("actor didn't respond")
          val out: Enumerator[String] = Enumerator.eof
          val in: Iteratee[String, Unit] = Iteratee.ignore
          (in, out)
      }
    }
  }

  case class StartMinecraftServer()

  case class SendMinecraftCommand(cmd: String)

  class MinecraftActor extends Actor {

    val jarURL = "../minecraft_server.1.8.jar"
    val file:File  = new File(jarURL)
    val url = file.toURL()
    val cl = new URLClassLoader(url)


    val clazz = cl.loadClass("my.pkg.MyActor")

    def receive = {
      case StartMinecraftServer() => {
        println("start minecraft server")
      }
      case SendMinecraftCommand(cmd) => {
        println(s"cmd: ${cmd}")
      }
    }
  }

  case class Start()

  case class ClientConnected()

  class WebsocketEchoActor extends Actor {

    override def receive = {
      case ClientConnected() =>
        var channel: Option[Concurrent.Channel[String]] = None
        val out: Enumerator[String] = Concurrent.unicast(c => channel = Some(c))
        val in = Iteratee.foreach[String](message => {
          Logger.info(s"actor, received message: $message")
          if (message == "fanculo") channel.foreach(_.eofAndEnd())
          else channel.foreach(_.push(message))
        })
        sender !(in, out)
    }
  }

  // proxies another webservice
  def httpWeatherProxy = Action.async {
    request => {
      Logger.info("httpWeatherProxy, client connected")
      val url = "http://api.openweathermap.org/data/2.5/weather?q=Amsterdam,nl"
      WS.url(url).get().map(r => Ok(r.body))
    }
  }

  // proxies another webservice, websocket style
  def wsWeatherProxy = WebSocket.async[String] {
    request => Future {
      Logger.info("wsWeatherProxy, client connected")
      val url = "http://api.openweathermap.org/data/2.5/weather?q=Amsterdam,nl"
      var switch = false
      val myEnumerator: Enumerator[String] = Enumerator.generateM[String](WS.url(url).get().map(r => {
        switch = !switch
        if (switch) Option(r.body)
        else None
      }))
      (Iteratee.ignore[String], myEnumerator)
    }
  }

  // proxies another webservice at regular intervals
  def wsWeatherIntervals = WebSocket.async[String] {
    request => Future {
      Logger.info("wsWeatherIntervals, client connected")
      val url = "http://api.openweathermap.org/data/2.5/weather?q=Amsterdam,nl"
      val outEnumerator = Enumerator.repeatM[String]({
        Thread.sleep(3000)
        WS.url(url).get().map(r => s"${new java.util.Date()}\n ${r.body}")
      })

      (Iteratee.ignore[String], outEnumerator)
    }
  }
}