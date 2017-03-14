package xyz.driver.core

import java.sql.SQLException

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.RouteResult._
import akka.http.scaladsl.server.{ExceptionHandler, Route, RouteConcatenation}
import akka.stream.ActorMaterializer
import com.typesafe.config.Config
import io.swagger.models.Scheme
import org.slf4j.{LoggerFactory, MDC}
import spray.json.DefaultJsonProtocol
import xyz.driver.core
import xyz.driver.core.logging.{Logger, TypesafeScalaLogger}
import xyz.driver.core.rest.ContextHeaders
import xyz.driver.core.rest.Swagger
import xyz.driver.core.stats.SystemStats
import xyz.driver.core.time.Time
import xyz.driver.core.time.provider.{SystemTimeProvider, TimeProvider}

import scala.compat.Platform.ConcurrentModificationException
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

object app {

  class DriverApp(version: String,
                  gitHash: String,
                  modules: Seq[Module],
                  time: TimeProvider = new SystemTimeProvider(),
                  log: Logger = new TypesafeScalaLogger(
                    com.typesafe.scalalogging.Logger(LoggerFactory.getLogger(classOf[DriverApp]))),
                  config: Config = core.config.loadDefaultConfig,
                  interface: String = "::0",
                  baseUrl: String = "localhost:8080",
                  scheme: String = "http",
                  port: Int = 8080)(implicit actorSystem: ActorSystem, executionContext: ExecutionContext) {

    implicit private lazy val materializer = ActorMaterializer()(actorSystem)
    private lazy val http                  = Http()(actorSystem)

    def run(): Unit = {
      activateServices(modules)
      scheduleServicesDeactivation(modules)
      bindHttp(modules)
      Console.print(s"${this.getClass.getName} App is started\n")
    }

    def stop(): Unit = {
      http.shutdownAllConnectionPools().onComplete { _ =>
        val _                 = actorSystem.terminate()
        val terminated        = Await.result(actorSystem.whenTerminated, 30.seconds)
        val addressTerminated = if (terminated.addressTerminated) "is" else "is not"
        Console.print(s"${this.getClass.getName} App $addressTerminated stopped ")
      }
    }

    protected def bindHttp(modules: Seq[Module]): Unit = {
      val serviceTypes   = modules.flatMap(_.routeTypes)
      val swaggerService = new Swagger(baseUrl, Scheme.forValue(scheme), version, actorSystem, serviceTypes, config)
      val swaggerRoutes  = swaggerService.routes ~ swaggerService.swaggerUI
      val versionRt      = versionRoute(version, gitHash, time.currentTime())

      val _ = Future {
        http.bindAndHandle(route2HandlerFlow(handleExceptions(ExceptionHandler(exceptionHandler)) { ctx =>
          val trackingId = rest.extractTrackingId(ctx.request)
          MDC.put("trackingId", trackingId)
          log.audit(s"Received request ${ctx.request}")

          val contextWithTrackingId =
            ctx.withRequest(ctx.request.addHeader(RawHeader(ContextHeaders.TrackingIdHeader, trackingId)))

          respondWithHeaders(List(RawHeader(ContextHeaders.TrackingIdHeader, trackingId))) {
            modules.map(_.route).foldLeft(versionRt ~ healthRoute ~ swaggerRoutes)(_ ~ _)
          }(contextWithTrackingId)
        }), interface, port)(materializer)
      }
    }

    /**
      * Override me for custom exception handling
      *
      * @return Exception handling route for exception type
      */
    protected def exceptionHandler = PartialFunction[Throwable, Route] {

      case is: IllegalStateException =>
        ctx =>
          val trackingId = Option(MDC.get("trackingId")).getOrElse(rest.extractTrackingId(ctx.request))
          log.debug(s"Request is not allowed to ${ctx.request.uri} ($trackingId)", is)
          complete(HttpResponse(BadRequest, entity = is.getMessage))(ctx)

      case cm: ConcurrentModificationException =>
        ctx =>
          val trackingId = Option(MDC.get("trackingId")).getOrElse(rest.extractTrackingId(ctx.request))
          log.audit(s"Concurrent modification of the resource ${ctx.request.uri} ($trackingId)", cm)
          complete(
            HttpResponse(Conflict, entity = "Resource was changed concurrently, try requesting a newer version"))(ctx)

      case sex: SQLException =>
        ctx =>
          val trackingId = Option(MDC.get("trackingId")).getOrElse(rest.extractTrackingId(ctx.request))
          log.audit(s"Database exception for the resource ${ctx.request.uri} ($trackingId)", sex)
          complete(HttpResponse(InternalServerError, entity = "Data access error"))(ctx)

      case t: Throwable =>
        ctx =>
          val trackingId = Option(MDC.get("trackingId")).getOrElse(rest.extractTrackingId(ctx.request))
          log.error(s"Request to ${ctx.request.uri} could not be handled normally ($trackingId)", t)
          complete(HttpResponse(InternalServerError, entity = t.getMessage))(ctx)
    }

    protected def versionRoute(version: String, gitHash: String, startupTime: Time): Route = {
      import DefaultJsonProtocol._
      import SprayJsonSupport._

      path("version") {
        val currentTime = time.currentTime().millis
        complete(
          Map(
            "version"     -> version,
            "gitHash"     -> gitHash,
            "modules"     -> modules.map(_.name).mkString(", "),
            "startupTime" -> startupTime.millis.toString,
            "serverTime"  -> currentTime.toString,
            "uptime"      -> (currentTime - startupTime.millis).toString
          ))
      }
    }

    protected def healthRoute: Route = {
      import DefaultJsonProtocol._
      import SprayJsonSupport._
      import spray.json._

      val memoryUsage = SystemStats.memoryUsage
      val gcStats     = SystemStats.garbageCollectorStats

      path("health") {
        complete(
          Map(
            "availableProcessors" -> SystemStats.availableProcessors.toJson,
            "memoryUsage" -> Map(
              "free"  -> memoryUsage.free.toJson,
              "total" -> memoryUsage.total.toJson,
              "max"   -> memoryUsage.max.toJson
            ).toJson,
            "gcStats" -> Map(
              "garbageCollectionTime"   -> gcStats.garbageCollectionTime.toJson,
              "totalGarbageCollections" -> gcStats.totalGarbageCollections.toJson
            ).toJson,
            "fileSystemSpace" -> SystemStats.fileSystemSpace.map { f =>
              Map("path"        -> f.path.toJson,
                  "freeSpace"   -> f.freeSpace.toJson,
                  "totalSpace"  -> f.totalSpace.toJson,
                  "usableSpace" -> f.usableSpace.toJson)
            }.toJson,
            "operatingSystem" -> SystemStats.operatingSystemStats.toJson
          ))
      }
    }

    /**
      * Initializes services
      */
    protected def activateServices(services: Seq[Module]): Unit = {
      services.foreach { service =>
        Console.print(s"Service ${service.name} starts ...")
        try {
          service.activate()
        } catch {
          case t: Throwable =>
            log.fatal(s"Service ${service.name} failed to activate", t)
            Console.print(" Failed! (check log)")
        }
        Console.print(" Done\n")
      }
    }

    /**
      * Schedules services to be deactivated on the app shutdown
      */
    protected def scheduleServicesDeactivation(services: Seq[Module]) = {
      Runtime.getRuntime.addShutdownHook(new Thread() {
        override def run(): Unit = {
          services.foreach { service =>
            Console.print(s"Service ${service.name} shutting down ...")
            try {
              service.deactivate()
            } catch {
              case t: Throwable =>
                log.fatal(s"Service ${service.name} failed to deactivate", t)
                Console.print(" Failed! (check log)")
            }
            Console.print(" Done\n")
          }
        }
      })
    }
  }

  import scala.reflect.runtime.universe._

  trait Module {
    val name: String
    def route: Route
    def routeTypes: Seq[Type]

    def activate(): Unit   = {}
    def deactivate(): Unit = {}
  }

  class EmptyModule extends Module {
    val name         = "Nothing"
    def route: Route = complete(StatusCodes.OK)
    def routeTypes   = Seq.empty[Type]
  }

  class SimpleModule(val name: String, val route: Route, routeType: Type) extends Module {
    def routeTypes: Seq[Type] = Seq(routeType)
  }

  /**
    * Module implementation which may be used to composed a few
    *
    * @param name more general name of the composite module,
    *             must be provided as there is no good way to automatically
    *             generalize the name from the composed modules' names
    * @param modules modules to compose into a single one
    */
  class CompositeModule(val name: String, modules: Seq[Module]) extends Module with RouteConcatenation {

    def route: Route = modules.map(_.route).reduce(_ ~ _)
    def routeTypes   = modules.flatMap(_.routeTypes)

    override def activate()   = modules.foreach(_.activate())
    override def deactivate() = modules.reverse.foreach(_.deactivate())
  }
}
