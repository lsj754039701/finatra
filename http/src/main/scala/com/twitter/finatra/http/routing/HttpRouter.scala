package com.twitter.finatra.http.routing

import com.twitter.finagle.Filter
import com.twitter.finatra.http.exceptions.{AbstractExceptionMapper, ExceptionManager, ExceptionMapper, ExceptionMapperCollection}
import com.twitter.finatra.http.internal.marshalling.{CallbackConverter, MessageBodyManager}
import com.twitter.finatra.http.internal.routing.{Route, RoutesByType, RoutingService, Services}
import com.twitter.finatra.http.marshalling.MessageBodyComponent
import com.twitter.finatra.http.routing.HttpRouter._
import com.twitter.finatra.http.{AbstractController, Controller, HttpFilter}
import com.twitter.inject.TypeUtils._
import com.twitter.inject.{Injector, Logging}
import java.lang.annotation.{Annotation => JavaAnnotation}
import javax.inject.{Inject, Singleton}
import scala.collection.mutable.ArrayBuffer

object HttpRouter {
  val FinatraAdminPrefix = "/admin/finatra/"
}

@Singleton
class HttpRouter @Inject()(
  injector: Injector,
  callbackConverter: CallbackConverter,
  messageBodyManager: MessageBodyManager,
  exceptionManager: ExceptionManager)
  extends Logging {

  /* Mutable State */
  private[finatra] var globalBeforeRouteMatchingFilter: HttpFilter = Filter.identity
  private[finatra] var globalFilter: HttpFilter = Filter.identity
  private[finatra] val routes = ArrayBuffer[Route]()

  private[finatra] lazy val routesByType = partitionRoutesByType()
  private[finatra] lazy val adminRoutingService = new RoutingService(routesByType.admin)
  private[finatra] lazy val externalRoutingService = new RoutingService(routesByType.external)
  private[finatra] lazy val services: Services = {
    Services(
      routesByType,
      adminService = globalBeforeRouteMatchingFilter andThen adminRoutingService,
      externalService = globalBeforeRouteMatchingFilter andThen externalRoutingService)
  }

  /* Public */

  def exceptionMapper[T <: ExceptionMapper[_] : Manifest]: HttpRouter = {
    exceptionManager.add[T]
    this
  }

  def exceptionMapper[T <: Throwable : Manifest](mapper: ExceptionMapper[T]): HttpRouter = {
    exceptionManager.add[T](mapper)
    this
  }

  def exceptionMapper[T <: Throwable](clazz: Class[_ <: AbstractExceptionMapper[T]]): HttpRouter = {
    val mapperType = superTypeFromClass(clazz, classOf[ExceptionMapper[_]])
    val throwableType = singleTypeParam(mapperType)
    exceptionMapper(injector.instance(clazz))(Manifest.classType(Class.forName(throwableType.getTypeName)))
    this
  }

  def exceptionMapper(mappers: ExceptionMapperCollection): HttpRouter = {
    exceptionManager.add(mappers)
    this
  }

  def register[MBR <: MessageBodyComponent : Manifest]: HttpRouter = {
    messageBodyManager.add[MBR]()
    this
  }

  def register[MBR <: MessageBodyComponent : Manifest, ObjTypeToReadWrite: Manifest]: HttpRouter = {
    messageBodyManager.addExplicit[MBR, ObjTypeToReadWrite]()
    this
  }

  /** Add global filter used for all requests annotated with Annotation Type */
  def filter[FilterType <: HttpFilter : Manifest, Ann <: JavaAnnotation : Manifest]: HttpRouter = {
    filter(injector.instance[FilterType, Ann])
  }

  /** Add global filter used for all requests, by default applied AFTER route matching */
  def filter(clazz: Class[_ <: HttpFilter]): HttpRouter = {
    filter(injector.instance(clazz))
  }

  /** Add global filter used for all requests, optionally BEFORE route matching */
  def filter(clazz: Class[_ <: HttpFilter], beforeRouting: Boolean): HttpRouter = {
    if (beforeRouting) {
      filter(injector.instance(clazz), beforeRouting = true)
    } else {
      filter(injector.instance(clazz))
    }
  }

  /** Add global filter used for all requests, by default applied AFTER route matching */
  def filter[FilterType <: HttpFilter : Manifest]: HttpRouter = {
    filter(injector.instance[FilterType])
  }

  /** Add global filter used for all requests, optionally BEFORE route matching */
  def filter[FilterType <: HttpFilter : Manifest](beforeRouting: Boolean): HttpRouter = {
    if (beforeRouting) {
      filter(injector.instance[FilterType], beforeRouting = true)
      this
    } else {
      filter(injector.instance[FilterType])
    }
  }

  /** Add global filter used for all requests, by default applied AFTER route matching */
  def filter(filter: HttpFilter): HttpRouter = {
    assert(routes.isEmpty, "'filter' must be called before 'add'.")
    globalFilter = globalFilter andThen filter
    this
  }

  /** Add global filter used for all requests, optionally BEFORE route matching */
  def filter(filter: HttpFilter, beforeRouting: Boolean): HttpRouter = {
    if (beforeRouting) {
      assert(routes.isEmpty && globalFilter == Filter.identity,
        "'filter[T](beforeRouting = true)' must be called before 'filter' or 'add'.")
      globalBeforeRouteMatchingFilter = globalBeforeRouteMatchingFilter andThen filter
      this
    } else {
      this.filter(filter)
    }
  }

  def add(controller: Controller): HttpRouter = {
    injector.underlying.injectMembers(controller)
    addInjected(controller)
  }

  /** Add per-controller filter (Note: Per-controller filters only run if the paired controller has a matching route) */
  def add(filter: HttpFilter, controller: Controller): HttpRouter = {
    injector.underlying.injectMembers(controller)
    addInjected(filter, controller)
  }

  def add[C <: Controller : Manifest]: HttpRouter = {
    val controller = injector.instance[C]
    addInjected(controller)
  }

  def add(clazz: Class[_ <: AbstractController]): HttpRouter = {
    val controller = injector.instance(clazz)
    controller.configureRoutes()
    addInjected(controller)
  }

  // Generated
  /* Note: If you have more than 10 filters, combine some of them using MergedFilter (@see CommonFilters) */
  /** Add per-controller filter (Note: Per-controller filters only run if the paired controller has a matching route) */
  def add[F1 <: HttpFilter : Manifest, C <: Controller : Manifest]: HttpRouter = add[C](injector.instance[F1])

  /** Add per-controller filters (Note: Per-controller filters only run if the paired controller has a matching route) */
  def add[F1 <: HttpFilter : Manifest, F2 <: HttpFilter : Manifest, C <: Controller : Manifest]: HttpRouter = add[C](injector.instance[F1] andThen injector.instance[F2])

  /** Add per-controller filters (Note: Per-controller filters only run if the paired controller has a matching route) */
  def add[F1 <: HttpFilter : Manifest, F2 <: HttpFilter : Manifest, F3 <: HttpFilter : Manifest, C <: Controller : Manifest]: HttpRouter = add[C](injector.instance[F1] andThen injector.instance[F2] andThen injector.instance[F3])

  /** Add per-controller filters (Note: Per-controller filters only run if the paired controller has a matching route) */
  def add[F1 <: HttpFilter : Manifest, F2 <: HttpFilter : Manifest, F3 <: HttpFilter : Manifest, F4 <: HttpFilter : Manifest, C <: Controller : Manifest]: HttpRouter = add[C](injector.instance[F1] andThen injector.instance[F2] andThen injector.instance[F3] andThen injector.instance[F4])

  /** Add per-controller filters (Note: Per-controller filters only run if the paired controller has a matching route) */
  def add[F1 <: HttpFilter : Manifest, F2 <: HttpFilter : Manifest, F3 <: HttpFilter : Manifest, F4 <: HttpFilter : Manifest, F5 <: HttpFilter : Manifest, C <: Controller : Manifest]: HttpRouter = add[C](injector.instance[F1] andThen injector.instance[F2] andThen injector.instance[F3] andThen injector.instance[F4] andThen injector.instance[F5])

  /** Add per-controller filters (Note: Per-controller filters only run if the paired controller has a matching route) */
  def add[F1 <: HttpFilter : Manifest, F2 <: HttpFilter : Manifest, F3 <: HttpFilter : Manifest, F4 <: HttpFilter : Manifest, F5 <: HttpFilter : Manifest, F6 <: HttpFilter : Manifest, C <: Controller : Manifest]: HttpRouter = add[C](injector.instance[F1] andThen injector.instance[F2] andThen injector.instance[F3] andThen injector.instance[F4] andThen injector.instance[F5] andThen injector.instance[F6])

  /** Add per-controller filters (Note: Per-controller filters only run if the paired controller has a matching route) */
  def add[F1 <: HttpFilter : Manifest, F2 <: HttpFilter : Manifest, F3 <: HttpFilter : Manifest, F4 <: HttpFilter : Manifest, F5 <: HttpFilter : Manifest, F6 <: HttpFilter : Manifest, F7 <: HttpFilter : Manifest, C <: Controller : Manifest]: HttpRouter = add[C](injector.instance[F1] andThen injector.instance[F2] andThen injector.instance[F3] andThen injector.instance[F4] andThen injector.instance[F5] andThen injector.instance[F6] andThen injector.instance[F7])

  /** Add per-controller filters (Note: Per-controller filters only run if the paired controller has a matching route) */
  def add[F1 <: HttpFilter : Manifest, F2 <: HttpFilter : Manifest, F3 <: HttpFilter : Manifest, F4 <: HttpFilter : Manifest, F5 <: HttpFilter : Manifest, F6 <: HttpFilter : Manifest, F7 <: HttpFilter : Manifest, F8 <: HttpFilter : Manifest, C <: Controller : Manifest]: HttpRouter = add[C](injector.instance[F1] andThen injector.instance[F2] andThen injector.instance[F3] andThen injector.instance[F4] andThen injector.instance[F5] andThen injector.instance[F6] andThen injector.instance[F7] andThen injector.instance[F8])

  /** Add per-controller filters (Note: Per-controller filters only run if the paired controller has a matching route) */
  def add[F1 <: HttpFilter : Manifest, F2 <: HttpFilter : Manifest, F3 <: HttpFilter : Manifest, F4 <: HttpFilter : Manifest, F5 <: HttpFilter : Manifest, F6 <: HttpFilter : Manifest, F7 <: HttpFilter : Manifest, F8 <: HttpFilter : Manifest, F9 <: HttpFilter : Manifest, C <: Controller : Manifest]: HttpRouter = add[C](injector.instance[F1] andThen injector.instance[F2] andThen injector.instance[F3] andThen injector.instance[F4] andThen injector.instance[F5] andThen injector.instance[F6] andThen injector.instance[F7] andThen injector.instance[F8] andThen injector.instance[F9])

  /** Add per-controller filters (Note: Per-controller filters only run if the paired controller has a matching route) */
  def add[F1 <: HttpFilter : Manifest, F2 <: HttpFilter : Manifest, F3 <: HttpFilter : Manifest, F4 <: HttpFilter : Manifest, F5 <: HttpFilter : Manifest, F6 <: HttpFilter : Manifest, F7 <: HttpFilter : Manifest, F8 <: HttpFilter : Manifest, F9 <: HttpFilter : Manifest, F10 <: HttpFilter : Manifest, C <: Controller : Manifest]: HttpRouter = add[C](injector.instance[F1] andThen injector.instance[F2] andThen injector.instance[F3] andThen injector.instance[F4] andThen injector.instance[F5] andThen injector.instance[F6] andThen injector.instance[F7] andThen injector.instance[F8] andThen injector.instance[F9] andThen injector.instance[F10])

  /* Private */

  private def add[C <: Controller : Manifest](filter: HttpFilter): HttpRouter = {
    addInjected(
      filter,
      injector.instance[C])
  }

  private def addInjected(controller: Controller): HttpRouter = {
    routes ++= buildRoutes(controller).map(_.withFilter(globalFilter))
    this
  }

  private def addInjected(filter: HttpFilter, controller: Controller): HttpRouter = {
    val routesWithFilter = buildRoutes(controller).map(_.withFilter(globalFilter.andThen(filter)))
    routes ++= routesWithFilter
    this
  }

  private def buildRoutes(controller: Controller): Seq[Route] = {
    controller.routeBuilders.map(_.build(callbackConverter, injector))
  }

  private[finatra] def partitionRoutesByType(): RoutesByType = {
    info("Adding routes\n" + routes.map(_.summary).mkString("\n"))
    val (adminRoutes, externalRoutes) = routes partition { route =>
      route.path.startsWith(FinatraAdminPrefix) || route.admin
    }
    assertAdminRoutes(adminRoutes)
    RoutesByType(
      external = externalRoutes.toSeq,
      admin = adminRoutes.toSeq)
  }

  // non-constant routes MUST start with /admin/finatra
  private def assertAdminRoutes(routes: ArrayBuffer[Route]): Unit = {
    val message = "Error adding route: %s. Non-constant admin interface routes must start with prefix: " + HttpRouter.FinatraAdminPrefix

    for (route <- routes) {
      if (!route.constantRoute) {
        // non-constant routes MUST start with /admin/finatra/
        if(!route.path.startsWith(HttpRouter.FinatraAdminPrefix)) {
          val msg = message.format(route.path)
          error(msg)
          throw new java.lang.AssertionError(msg)
        }
      }
    }
  }
}
