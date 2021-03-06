package com.twitter.finagle.mux.lease

import com.twitter.finagle._
import com.twitter.util.{Future, Time}

object LeasedFactory {
  object Leased extends Stack.Role

  def module[Req, Rep]: Stackable[ServiceFactory[Req, Rep]] =
    new Stack.Simple[ServiceFactory[Req, Rep]](Leased) {
      val description = "Bridge gap between ServiceFactory.isAvailable and Acting.isActive"
      def make(params: Params, next: ServiceFactory[Req, Rep]) = {
        val mk: () => Future[Service[Req, Rep] with Acting] = { () =>
          next() map {
            // the warning is OK, this should go away when we fix the abstraction
            case svc: Service[Req, Rep] with Acting => svc
            case _ => throw new IllegalArgumentException("You are only permitted to pass Acting Services!")
          }
        }
        new LeasedFactory(mk)
      }
    }
}

/**
 * LeasedFactory is used for bridging the gap between ServiceFactory#isAvailable
 * and Acting#isActive.  It will be removed after Service has a higher fidelity
 * way of doing State inspection.
 */
private[finagle] class LeasedFactory[Req, Rep](mk: () => Future[Service[Req, Rep] with Acting])
    extends ServiceFactory[Req, Rep] {
  private[this] var current: List[Acting] = Nil

  private[this] def newService(svc: Service[Req, Rep] with Acting) = {
    synchronized {
      current ::= svc
    }
    new ServiceProxy(svc) {
      override def close(deadline: Time) = {
        synchronized {
          current = current.filterNot(_ == svc)
        }
        super.close(deadline)
      }
    }
  }

  def apply(conn: ClientConnection): Future[Service[Req, Rep]] =
    mk() map { dispatcher =>
      newService(dispatcher)
    }

  override def isAvailable: Boolean = current forall (_.isActive)

  def close(deadline: Time): Future[Unit] = Future.Done
}
