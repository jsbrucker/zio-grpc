package scalapb.zio_grpc.server

import io.grpc.ServerCall.Listener
import io.grpc.Status
import zio._
import zio.stream.Stream
import io.grpc.Metadata

/** Represents a running request to be served by [[ZServerCallHandler]]
  *
  * The listener is returned to grpc-java to feed input into the request.
  *
  * The `run` represents an effect of running the request: it reads the
  * input provided to the listener, and writes the response to an output
  * channel. It handles interrupts by sending a cancel event through the
  * channel.
  */
case class CallDriver[R, Req](
    listener: Listener[Req],
    run: ZIO[R, Status, Unit]
)

object CallDriver {
  def exitToStatus(ex: Exit[Status, Unit]): Status =
    ex.fold(failed = { cause =>
      if (cause.interrupted) Status.CANCELLED
      else cause.failureOption.getOrElse(Status.INTERNAL)
    }, completed = _ => Status.OK)

  def unaryInputCallDriver[R, Req](
      runtime: Runtime[R],
      call: ZServerCall[_],
      cancelled: Promise[Nothing, Unit],
      completed: Promise[Status, Unit],
      request: Promise[Nothing, Req],
      writeResponse: Req => ZIO[R, Status, Unit]
  ): CallDriver[R, Req] = CallDriver(
    listener = new Listener[Req] {
      override def onCancel(): Unit =
        runtime.unsafeRun(cancelled.succeed(()).unit)

      override def onHalfClose(): Unit =
        runtime.unsafeRun(completed.completeWith(ZIO.unit).unit)

      override def onMessage(message: Req): Unit = {
        runtime.unsafeRun {
          request.succeed(message).flatMap {
            case false =>
              completed
                .fail(Status.INTERNAL.withDescription("Too many requests"))
                .unit
            case true =>
              ZIO.unit
          }
        }
      }
    },
    run = (
      call.request(2) *>
        completed.await *>
        call.sendHeaders(new Metadata) *>
        request.await >>= writeResponse
    ).onExit(ex =>
        call.close(CallDriver.exitToStatus(ex), new Metadata).ignore
      )
      .ignore
      .race(cancelled.await)
  )

  /**
    * Creates a [[CallDriver]] for a request with a unary input.
    *
    * writeResponse: given a request, returns a effects that computes
    * the response and writes it through the given ZServerCall.
    */
  def makeUnaryInputCallDriver[R, Req, Res](
      writeResponse: (Req, Metadata, ZServerCall[Res]) => ZIO[R, Status, Unit]
  )(
      zioCall: ZServerCall[Res],
      metadata: Metadata
  ): ZIO[R, Nothing, CallDriver[R, Req]] = {
    for {
      runtime <- ZIO.runtime[R]
      cancelled <- Promise.make[Nothing, Unit]
      completed <- Promise.make[Status, Unit]
      request <- Promise.make[Nothing, Req]
    } yield unaryInputCallDriver(
      runtime,
      zioCall,
      cancelled,
      completed,
      request,
      writeResponse(_, metadata, zioCall)
    )
  }

  def streamingInputCallDriver[R, Req, Res](
      runtime: Runtime[R],
      call: ZServerCall[Res],
      cancelled: Promise[Nothing, Unit],
      queue: Queue[Either[Option[Status], Req]],
      writeResponse: Stream[Status, Req] => ZIO[R, Status, Unit]
  ): CallDriver[R, Req] = CallDriver(
    listener = new Listener[Req] {
      override def onCancel(): Unit =
        runtime.unsafeRun(cancelled.succeed(()).unit)

      override def onHalfClose(): Unit = {
        runtime.unsafeRun(queue.offer(Left(None)).unit)
      }

      override def onMessage(message: Req): Unit = {
        runtime.unsafeRun(
          call.request(1) *> queue.offer(Right(message)).unit
        )
      }
    },
    run = {
      val requestStream = Stream
        .fromQueue(queue)
        .tap {
          case Left(None) => queue.shutdown
          case Left(Some(status)) =>
            queue.shutdown *> ZIO.fail(status)
          case _ => ZIO.unit
        }
        .collect {
          case Right(v) => v
        }

      (call.request(1) *>
        call.sendHeaders(new Metadata) *>
        writeResponse(requestStream))
        .onExit(ex =>
          call.close(CallDriver.exitToStatus(ex), new Metadata).ignore
        )
        .ignore
        .race(cancelled.await)
    }
  )

  /**
    * Creates a [[CallDriver]] for a request with a streaming input.
    *
    * writeResponse: given a request, returns a effects that computes
    * the response and writes it through the given ZServerCall.
    */
  def makeStreamingInputCallDriver[R, Req, Res](
      writeResponse: (
          Stream[Status, Req],
          Metadata,
          ZServerCall[Res]
      ) => ZIO[R, Status, Unit]
  )(
      zioCall: ZServerCall[Res],
      metadata: Metadata
  ): ZIO[R, Nothing, CallDriver[R, Req]] = {
    for {
      runtime <- ZIO.runtime[R]
      cancelled <- Promise.make[Nothing, Unit]
      queue <- Queue.unbounded[Either[Option[Status], Req]]
    } yield streamingInputCallDriver[R, Req, Res](
      runtime,
      zioCall,
      cancelled,
      queue,
      writeResponse(_, metadata, zioCall)
    )
  }
}
