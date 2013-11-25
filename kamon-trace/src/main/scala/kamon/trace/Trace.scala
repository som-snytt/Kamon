package kamon.trace

import kamon.Kamon
import scala.util.DynamicVariable
import akka.actor._
import scala.Some
import kamon.trace.Trace.Register
import scala.concurrent.duration._
import java.util.concurrent.atomic.AtomicLong

object Trace extends ExtensionId[TraceExtension] with ExtensionIdProvider {
  def lookup(): ExtensionId[_ <: Extension] = Trace
  def createExtension(system: ExtendedActorSystem): TraceExtension = new TraceExtension(system)


  /*** Protocol */
  case object Register



  /** User API */
  private[trace] val traceContext = new DynamicVariable[Option[TraceContext]](None)
  private[trace] val tranid = new AtomicLong()


  def context() = traceContext.value
  def set(ctx: TraceContext) = traceContext.value = Some(ctx)

  def clear: Unit = traceContext.value = None
  def start(name: String)(implicit system: ActorSystem): TraceContext = {
    val ctx = newTraceContext(name)
    ctx.start(name)
    set(ctx)

    ctx
  }

  def withContext[T](ctx: Option[TraceContext])(thunk: => T): T = traceContext.withValue(ctx)(thunk)

  def transformContext(f: TraceContext => TraceContext): Unit = {
    context.map(f).foreach(set(_))
  }

  def finish(): Option[TraceContext] = {
    val ctx = context()
    ctx.map(_.finish)
    ctx
  }

  // TODO: FIX
  def newTraceContext(name: String)(implicit system: ActorSystem): TraceContext = TraceContext(Kamon(Trace), tranid.getAndIncrement, name)
}

class TraceExtension(system: ExtendedActorSystem) extends Kamon.Extension {
  val manager: ActorRef = system.actorOf(Props[TraceManager], "kamon-trace")
}

class TraceManager extends Actor with ActorLogging {
  var listeners: Seq[ActorRef] = Seq.empty

  def receive = {
    case Register =>
      listeners = sender +: listeners
      log.info("Registered [{}] as listener for Kamon traces", sender)

    case segment: UowSegment =>
      val tracerName = segment.id.toString
      context.child(tracerName).getOrElse(newTracer(tracerName)) ! segment

    case trace: UowTrace =>
      listeners foreach(_ ! trace)
  }

  def newTracer(name: String): ActorRef = {
    context.actorOf(UowTraceAggregator.props(self, 30 seconds), name)
  }
}