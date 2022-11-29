package content
import zio.{ExitCode, Has, Task, URIO, ZIO, ZLayer}
import zio.console._

case class User(name: String, email: String)

object UserEmailer {
  // type alias to use for other layers
  type UserEmailerEnv = Has[UserEmailer.Service]

  // service definition
  trait Service {
    def notify(u: User, msg: String): Task[Unit]
  }

  // layer; includes service implementation
  val live: ZLayer[Any, Nothing, UserEmailerEnv] = ZLayer.succeed(new Service {
    override def notify(u: User, msg: String): Task[Unit] =
      Task {
        println(s"[Email service] Sending $msg to ${u.email}")
      }
  })

  // front-facing API, aka "accessor"
  def notify(u: User, msg: String): ZIO[UserEmailerEnv, Throwable, Unit] = ZIO.accessM(_.get.notify(u, msg))
}

object UserDb {
  // type alias, to use for other layers
  type UserDbEnv = Has[UserDb.Service]

  // service definition
  trait Service {
    def insert(user: User): Task[Unit]
  }

  // layer - service implementation
  val live: ZLayer[Any, Nothing, UserDbEnv] = ZLayer.succeed {
    new Service {
      override def insert(user: User): Task[Unit] = Task {
        // can replace this with an actual DB SQL string
        println(s"[Database] insert into public.user values ('${user.name}')")
      }
    }
  }

  // accessor
  def insert(u: User): ZIO[UserDbEnv, Throwable, Unit] = ZIO.accessM(_.get.insert(u))
}


object UserSubscription {
  import UserEmailer._
  import UserDb._

  // type alias
  type UserSubscriptionEnv = Has[UserSubscription.Service]

  // service definition
  class Service(notifier: UserEmailer.Service, userModel: UserDb.Service) {
    def subscribe(u: User): Task[User] = {
      for {
        _ <- userModel.insert(u)
        _ <- notifier.notify(u, s"Welcome, ${u.name}! Here are some ZIO articles for you here at Rock the JVM.")
      } yield u
    }
  }

  // layer with service implementation via dependency injection
  val live: ZLayer[UserEmailerEnv with UserDbEnv, Nothing, UserSubscriptionEnv] =
    ZLayer.fromServices[UserEmailer.Service, UserDb.Service, UserSubscription.Service] { (emailer, db) =>
      new Service(emailer, db)
    }

  // accessor
  def subscribe(u: User): ZIO[UserSubscriptionEnv, Throwable, User] = ZIO.accessM(_.get.subscribe(u))
}

object ZLayersPlayground extends zio.App {
  override def run(args: List[String]): ZIO[zio.ZEnv, Nothing, ExitCode] = {
    val userRegistrationLayer = (UserDb.live ++ UserEmailer.live) >>> UserSubscription.live

    UserSubscription.subscribe(User("daniel", "daniel@rockthejvm.com"))
      .provideLayer(userRegistrationLayer)
      .catchAll(t => ZIO.succeed(t.printStackTrace()).map(_ => ExitCode.failure))
      .map { u =>
        println(s"Registered user: $u")
        ExitCode.success
      }
  }
}