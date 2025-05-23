/*
 * Scala.js (https://www.scala-js.org/)
 *
 * Copyright EPFL.
 *
 * Licensed under Apache License 2.0
 * (https://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package org.scalajs.testing.adapter

import scala.concurrent._
import scala.concurrent.duration._
import scala.collection.concurrent.TrieMap

import org.scalajs.logging._

import org.scalajs.jsenv._
import org.scalajs.jsenv.JSUtils.escapeJS

import org.scalajs.testing.common._

import sbt.testing.Framework

final class TestAdapter(jsEnv: JSEnv, input: Seq[Input], config: TestAdapter.Config) {

  import TestAdapter._

  require(input.nonEmpty,
      "Attempted to create a TestAdapter with empty input. " +
      "This will not work, since the TestAdapter expects replies from the JS end.")

  /** Map of ThreadId -> ManagedRunner */
  private[this] val runners = TrieMap.empty[Long, ManagedRunner]

  /** State management. May only be accessed under synchronization. */
  private[this] var closed = false
  private[this] var nextRunID = 0
  private[this] var runs = Set.empty[RunMux.RunID]

  /** A custom execution context that delegates to the global one for execution,
   *  but handles failures internally.
   */
  private implicit val executionContext: ExecutionContext =
    ExecutionContext.fromExecutor(ExecutionContext.global, reportFailure)

  /** Accessor to the deprecated method `jl.Thread.getId()`.
   *
   *  Since JDK 19, Thread.getId() is deprecated in favor of Thread.threadId().
   *  The only reason is that getId() was not marked `final`, and hence there
   *  was no guarantee that subclasses wouldn't override it with something that
   *  is not the thread's ID.
   *
   *  We cannot directly use Thread.threadId() since it was only added in JDK 19.
   *  Since we probably don't need to care about the potential "threat", we do
   *  the override-with-deprecated dance to silence the warning.
   *
   *  Reminder: we cannot use `@nowarn` since it was only introduced in
   *  Scala 2.12.13/2.13.2.
   */
  private val threadIDAccessor: ThreadIDAccessor = new ThreadIDAccessor {
    @deprecated("warning silencer", since = "forever")
    def getCurrentThreadId(): Long =
      Thread.currentThread().getId()
  }

  /** Creates an `sbt.testing.Framework` for each framework that can be found.
   *
   *  The returned Frameworks bind to this TestAdapter and are only valid until
   *  [[close]] is called.
   */
  def loadFrameworks(frameworkNames: List[List[String]]): List[Option[Framework]] = {
    getRunnerForThread().com
      .call(JSEndpoints.detectFrameworks)(frameworkNames)
      .map(_.map(_.map(info => new FrameworkAdapter(info, this))))
      .await()
  }

  /** Releases all resources. All associated runs must be done. */
  def close(): Unit = synchronized {
    val runInfo =
      if (runs.isEmpty) "All runs have completed."
      else s"Incomplete runs: $runs"

    val msg = "TestAdapter.close() was called. " + runInfo

    if (runs.nonEmpty)
      config.logger.warn(msg)

    /* This is the exception callers will see if they are still pending.
     * That's why it is an IllegalStateException.
     */
    val cause = new IllegalStateException(msg)
    stopEverything(cause)
  }

  /** Called when a throwable bubbles up the execution stack.
   *
   *  We terminate everything if this happens to make sure nothing hangs waiting
   *  on an async operation to complete.
   */
  private def reportFailure(cause: Throwable): Unit = {
    val msg = "Failure in async execution. Aborting all test runs."
    val error = new AssertionError(msg, cause)
    config.logger.error(msg)
    config.logger.trace(error)
    stopEverything(error)
  }

  private def stopEverything(cause: Throwable): Unit = synchronized {
    if (!closed) {
      closed = true
      runners.values.foreach(_.com.close(cause))
      runners.clear()
    }
  }

  private[adapter] def runStarting(): RunMux.RunID = synchronized {
    require(!closed, "We are closed. Cannot create new run.")
    val runID = nextRunID
    nextRunID += 1
    runs += runID
    runID
  }

  /** Called by [[RunnerAdapter]] when the run is completed. */
  private[adapter] def runDone(runID: RunMux.RunID): Unit = synchronized {
    require(runs.contains(runID), s"Tried to remove nonexistent run $runID")
    runs -= runID
  }

  private[adapter] def getRunnerForThread(): ManagedRunner = {
    val threadId = threadIDAccessor.getCurrentThreadId()

    // Note that this is thread safe, since each thread can only operate on
    // the value associated to its thread id.
    runners.getOrElseUpdate(threadId, startManagedRunner(threadId))
  }

  private def startManagedRunner(threadId: Long): ManagedRunner = synchronized {
    // Prevent runners from being started after we are closed.
    // Otherwise we might leak runners.
    require(!closed, "We are closed. Cannot create new runner.")

    val com = new JSEnvRPC(jsEnv, input, config.logger, config.env)
    val mux = new RunMuxRPC(com)

    new ManagedRunner(threadId, com, mux)
  }
}

object TestAdapter {
  final class Config private (
      val logger: Logger,
      val env: Map[String, String]
  ) {
    private def this() = {
      this(
          logger = NullLogger,
          env = Map.empty
      )
    }

    def withLogger(logger: Logger): Config =
      copy(logger = logger)

    def withEnv(env: Map[String, String]): Config =
      copy(env = env)

    private def copy(
        logger: Logger = logger,
        env: Map[String, String] = env
    ): Config = {
      new Config(logger, env)
    }
  }

  object Config {
    def apply(): Config = new Config()
  }

  private[adapter] final class ManagedRunner(
      val id: Long,
      val com: RPCCore,
      val mux: RunMuxRPC
  )

  private abstract class ThreadIDAccessor {
    def getCurrentThreadId(): Long
  }
}
