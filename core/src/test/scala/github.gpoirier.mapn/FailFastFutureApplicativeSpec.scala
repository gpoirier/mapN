package github.gpoirier.mapn

import java.util.concurrent.{Executors, CountDownLatch}
import scala.concurrent._
import duration._

import org.scalatest.{Matchers, FlatSpec}

object FailFastFutureApplicativeSpec extends Matchers {

  implicit val ec = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(10))

  class TestFailure extends Exception

  class AsyncFixture(count: Int) {

    val failureTimeout = 50.millis
    val successTimeout = 50.millis

    val latch = new CountDownLatch(count)

    def blockedFuture[T](result: => T): Future[T] = Future {
      latch.await(1, SECONDS)
      result
    }

    def countDownFuture[T](result: => T): Future[T] = Future {
      latch.countDown()
      latch.await(1, SECONDS)
      result
    }

    def successValue[T](f: Future[T]): T =
      try {
        Await.result(f, successTimeout)
      } catch {
        case e: TimeoutException => fail(e)
      }

    def assertBlocked[T](f: Future[T]): Unit =
      a[TimeoutException] should be thrownBy Await.result(f, failureTimeout)
  }
}

class FailFastFutureApplicativeSpec extends FlatSpec with Matchers {

  import FailFastFutureApplicativeSpec._

  "for-comprehension" should "fail to start futures in parallel" in new AsyncFixture(1) {
    val result = for {
      a <- blockedFuture(10)
      b <- countDownFuture(20)
      x = a + b
      c <- blockedFuture(30)
    } yield {
      x + c
    }

    // For comprehensions will not create futures until the previous one completed
    assertBlocked(result)
  }

  "pre-started futures in for-comprehension" should "start futures in parallel" in new AsyncFixture(1) {
    val fa = blockedFuture(10)
    val fb = countDownFuture(20)
    val fc = blockedFuture(30)
    val result = for {
      a <- fa
      b <- fb
      x = a + b
      c <- fc
    } yield {
      x + c
    }

    successValue(result) shouldBe 60
  }

  it should "not fail fast" in new AsyncFixture(1) {
    val fa = blockedFuture(10)
    val fb = Future[Int](throw new TestFailure)
    val fc = blockedFuture(30)
    val result = for {
      a <- fa
      b <- fb
      x = a + b
      c <- fc
    } yield {
      x + c
    }

    assertBlocked(result)
  }

  "mapN" should "starts futures in parallel" in new AsyncFixture(1) {

    val result = mapN {
      val a = use(blockedFuture(10))
      val b = use(countDownFuture(20))
      val x = a + b

      val c = use(blockedFuture(30))
      x + c
    }

    successValue(result) shouldBe 60
  }

  it should "fail fast" in new AsyncFixture(1) {

    val result = mapN {
      val a = use(blockedFuture(10))
      val b = use(Future[Int](throw new TestFailure))
      val c = use(blockedFuture(30))

      a + b + c
    }

    a[TestFailure] should be thrownBy successValue(result)
  }

}
