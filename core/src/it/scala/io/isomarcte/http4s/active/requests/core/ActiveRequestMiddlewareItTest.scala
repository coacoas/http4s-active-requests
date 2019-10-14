package io.isomarcte.http4s.active.requests.core

import cats.data._
import cats.effect._
import cats.effect.concurrent._
import cats.implicits._
import fs2._
import fs2.concurrent._
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.http4s._
import org.http4s.client._
// import org.http4s.client.blaze._
// import org.http4s.client.dsl.io._
import org.http4s.dsl._
import org.http4s.implicits._
import org.http4s.server._
import org.http4s.server.blaze._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
// import org.http4s.client.asynchttpclient.AsyncHttpClient
import org.http4s.client.blaze.BlazeClientBuilder


final class ActiveRequestMiddlewareItTest extends BaseTest {
  import ActiveRequestMiddlewareItTest._

  "Running requests through a live http4s blaze server" should "correctly increment and decrement the active request counter" in io {
    val postBody: String = "K&R"
    testServer(ioTimer,
      Test[IO](
        request = (uri: Uri) => IO.pure(
          Request(
            uri = uri,
            method = Method.POST,
            body = Stream.emit(postBody)
              .covary[IO]
              .observe(_.evalMap(s => IO(println(s"Sending $s"))))
              .through(text.utf8Encode),
            headers = Headers.of(
              headers.`Content-Type`(MediaType.text.plain),
              headers.`Transfer-Encoding`(TransferCoding.chunked)
            )
          )
        ),
        beforeServerHasRecievedRequest = (l: Long) => IO(l shouldBe 0L).void,
        whileServerIsProcessingRequest = (l: Long) => IO(l shouldBe 1L).void,
        afterServerHasCompletedRequest = (l: Long) => IO(l shouldBe 1L).void,
        activeRequestMiddlewareCompleted = (l: Long) => IO(l shouldBe 0L).void,
        responseTest = (response: Response[IO]) => IO(println(s"Response: $response")) *>
          response.body.through(text.utf8Decode).compile.string
          .flatMap(s => IO(s shouldBe postBody).void)
      )
    )
  }
}

object ActiveRequestMiddlewareItTest {

  final case class Test[F[_]](
    request: Uri => F[Request[F]],
    beforeServerHasRecievedRequest: Long => F[Unit],
    whileServerIsProcessingRequest: Long => F[Unit],
    afterServerHasCompletedRequest: Long => F[Unit],
    activeRequestMiddlewareCompleted: Long => F[Unit],
    responseTest: Response[F] => F[Unit]
  )

  final case class TestData[F[_]](
    baseUri: Uri,
    testClient: Client[F]
  )

  implicit val ioTimer: Timer[IO] =
    IO.timer(ExecutionContext.global)

  implicit val ioContextShift: ContextShift[IO] =
    IO.contextShift(ExecutionContext.global)

  private[this] val testAddress: InetAddress =
    InetAddress.getLoopbackAddress()

  private[ActiveRequestMiddlewareItTest] lazy val blockingEC: ExecutionContext =
    ExecutionContext.fromExecutorService(
      Executors.newCachedThreadPool()
    )

  // private[this] def testClient[F[_]: Async : ContextShift]: Stream[F, Client[F]] =
  //   JavaNetClientBuilder.apply[F](this.blockingEC).stream

  private[this] def testClient[F[_]: ConcurrentEffect : ContextShift]: Stream[F, Client[F]] =
    BlazeClientBuilder[F](this.blockingEC).stream

  // private[this] def testClient[F[_]: ConcurrentEffect : ContextShift]: Stream[F, Client[F]] =
  //   AsyncHttpClient.stream()

  private[this] def freePort[F[_]](implicit F: Sync[F]): F[Int] =
    F.delay(new ServerSocket(0)).map(_.getLocalPort())

  private[this] def routes[F[_]](
    client: Client[F],
    uri: Uri
  )(
    implicit F: Concurrent[F]
  ): HttpRoutes[F] = {
    val dsl: Http4sDsl[F] = Http4sDsl[F]
    import dsl._
    HttpRoutes.of {
      case req @ POST -> Root =>
        req
          .bodyAsText
          .observe(_.evalMap(s => F.delay(println(s"Recieved body: [$s]"))))
          .compile
          .foldMonoid
          .flatMap { s  =>
            val newRequest = req
              .removeHeader(headers.Host)
              .withUri(uri)
              .withEntity(Stream(s).through(text.utf8Encode).covary[F])
            (F.delay(println(s"Primary server: Strict body [$s]"))
              *> F.delay(println(newRequest))
              *> client.toHttpApp.run(newRequest))
          }
    }
  }

  private[this] def proxyRoutes[F[_]: Sync]: HttpRoutes[F] = {
    val dsl: Http4sDsl[F] = Http4sDsl[F]
    import dsl._
    Http[F, F]{
      case req =>
        Sync[F].delay(println(s"Proxy route received request $req")) *>
        Ok(req.body)
    }.mapF(OptionT.liftF(_))
  }

  private[this] def routesBracket[F[_]](
    serverHasRequest: F[Unit],
    serverCompletedRequest: F[Unit]
  )(implicit F: Sync[F]): HttpMiddleware[F] = (service: HttpRoutes[F]) =>
  Kleisli((request: Request[F]) =>
    service.flatMapF((response: Response[F]) =>
      OptionT(
        serverHasRequest *>
        F.pure(response.copy(body = response.body.onFinalize(serverCompletedRequest)).some)
      )
    ).run(request)
  )

  private[this] def blaze[F[_]: ConcurrentEffect](
    port: Int,
    timer: Timer[F]
  ): BlazeServerBuilder[F] = {
    implicit val t: Timer[F] = timer
    BlazeServerBuilder[F].bindSocketAddress(
      InetSocketAddress.createUnresolved(this.testAddress.getHostAddress(), port)
    )
  }

  def testServer(
    timer: Timer[IO],
    test: Test[IO]
  ): IO[Unit] = {

    val port: IO[Int] = for {
      fp <- this.freePort[IO]// (sp, 10)
      _ <- IO(println(s"Port Selected For Test Server: $fp"))
    } yield fp

    Resource.make(
      SignallingRef[IO, Boolean](false)
    )(_.set(true) *> IO(println("Server Shutdown Signaled"))).use((signallingRef: SignallingRef[IO, Boolean]) =>
      Stream.eval(for {
        p <- port
        p2 <- port
        serverHasRequest <- Deferred[IO, Unit]
        processTestComplete <- Deferred[IO, Unit]
        serverCompletedRequest <- Deferred[IO, Unit]
        activeRequestMiddlewareStarted <- Deferred[IO, Unit]
        activeRequestMiddlewareCompleted <- Deferred[IO, Unit]
        uri <- IO.fromEither(Uri.fromString(s"http://${this.testAddress.getHostName}:${p}/"))
        uri2 <- IO.fromEither(Uri.fromString(s"http://${this.testAddress.getHostName}:${p2}/"))
        ref <- Ref.of[IO, ExitCode](ExitCode.Success)
        state <- Ref.of[IO, Long](0L)
        arm <- ActiveRequestMiddleware.serviceUnavailableMiddleware_(
          state.set _,
          (l) => activeRequestMiddlewareStarted.get *> state.set(l) *> activeRequestMiddlewareCompleted.complete(()) *> IO(println(s"Finalized responsetest ($l)")),
          IO.unit,
          2
        )
      } yield {
        val wrappedRoutes: Stream[IO, HttpRoutes[IO]] =
          this.testClient[IO].map((c: Client[IO]) =>
            arm(
              this.routesBracket[IO](
                serverHasRequest.complete(()),
                processTestComplete.get *> serverCompletedRequest.complete(())
              )(
                Sync[IO]
              )(
                this.routes[IO](c, uri2)
              )
            )
          )
        val testF: Stream[IO, Unit] =
          this.testClient[IO].flatMap((client: Client[IO]) =>
            Stream.eval(for {
              _ <- IO(println("Starting"))
              s0 <- state.get
              _ <- IO(println(s"Initial state: $s0"))
              _ <- test.beforeServerHasRecievedRequest(s0)
              request <- test.request(uri)
              _ <- IO(println(s"Sending $request"))
              fiber0 <- (IO.shift(this.blockingEC) *> client.fetch(request)(test.responseTest)).start
              _ <- serverHasRequest.get
              _ <- IO(println("Server has request"))
              s1 <- state.get
              _ <- IO(println(s"State is $s1"))
              _ <- test.whileServerIsProcessingRequest(s1)
              _ <- IO(println("Server is processing"))
              _ <- processTestComplete.complete(())
              _ <- IO(println("processTestComplete"))
              _ <- serverCompletedRequest.get
              _ <- IO(println("Server has completed request"))
              s2 <- state.get
              _ <- IO(println(s"State is now $s2"))
              _ <- test.afterServerHasCompletedRequest(s2)
              _ <- IO(println("Test passed"))
              _ <- activeRequestMiddlewareStarted.complete(())
              _ <- IO(println("Allow middleware to complete"))
              _ <- activeRequestMiddlewareCompleted.get
              _ <- IO(println("Middleware has completed"))
              s3 <- state.get
              _ <- IO(println(s"State is now $s3"))
              _ <- test.activeRequestMiddlewareCompleted(s3)
              _ <- IO(println("done"))
              result <- fiber0.join
              _ <- IO(println("Joined"))
            } yield result)
          )

        val testStream: Stream[IO, Unit] =
          testF.delayBy(FiniteDuration(50, TimeUnit.MILLISECONDS))

        wrappedRoutes.flatMap(wr =>
          (this.blaze(p, timer).withHttpApp(wr.orNotFound).serveWhile(
            signallingRef,
            ref
          ).merge(
            this.blaze(p2, timer).withHttpApp(this.proxyRoutes[IO].orNotFound).serveWhile(
              signallingRef,
              ref
            )
          )).evalMap{
            case ExitCode.Success =>
              IO(println("Server Shutdown Success"))
            case otherwise =>
              val errorString: String = s"Invalid ExitCode: $otherwise"
              IO(println(errorString)) *>
              IO.raiseError[Unit](new AssertionError(errorString))
          }.onFinalize(IO(println("Server Shutdown Finalized")))
        ).mergeHaltR(testStream)
      }).flatten.compile.drain *>
        IO(println("Server Shutdown Completed"))
    ) *> IO(println("Resource Complete"))
  }
}
