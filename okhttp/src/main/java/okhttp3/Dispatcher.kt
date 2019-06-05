/*
 * Copyright (C) 2013 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3

import okhttp3.RealCall.AsyncCall
import okhttp3.internal.threadFactory
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Policy on when async requests are executed.
 *
 *
 * Each dispatcher uses an [ExecutorService] to run calls internally. If you supply your own
 * executor, it should be able to run [the configured maximum][maxRequests] number of calls
 * concurrently.
 */
class Dispatcher constructor() {
  /**
   * Set the maximum number of requests to execute concurrently. Above this requests queue in
   * memory, waiting for the running calls to complete.
   *
   * If more than `maxRequests` requests are in flight when this is invoked, those requests
   * will remain in flight.
   */
  @get:Synchronized var maxRequests = 64
    set(maxRequests) {
      require(maxRequests >= 1) { "max < 1: $maxRequests" }
      synchronized(this) {
        field = maxRequests
      }
      promoteAndExecute()
    }

  /**
   * Set the maximum number of requests for each host to execute concurrently. This limits requests
   * by the URL's host name. Note that concurrent requests to a single IP address may still exceed
   * this limit: multiple hostnames may share an IP address or be routed through the same HTTP
   * proxy.
   *
   * If more than `maxRequestsPerHost` requests are in flight when this is invoked, those
   * requests will remain in flight.
   *
   * WebSocket connections to hosts **do not** count against this limit.
   */
  @get:Synchronized var maxRequestsPerHost = 5
    set(maxRequestsPerHost) {
      require(maxRequestsPerHost >= 1) { "max < 1: $maxRequestsPerHost" }
      synchronized(this) {
        field = maxRequestsPerHost
      }
      promoteAndExecute()
    }

  private var idleCallback: Runnable? = null

  /** Executes calls. Created lazily.  */
  private var executorService: ExecutorService? = null

  /** Ready async calls in the order they'll be run.  */
  // 待执行的异步请求队列
  private val readyAsyncCalls = ArrayDeque<AsyncCall>()

  /** Running asynchronous calls. Includes canceled calls that haven't finished yet.  */
  // 异步运行队列，包括已取消未结束的请求
  private val runningAsyncCalls = ArrayDeque<AsyncCall>()

  /** Running synchronous calls. Includes canceled calls that haven't finished yet.  */
  // 同步运行队列，包括已取消未结束的请求
  private val runningSyncCalls = ArrayDeque<RealCall>()

  constructor(executorService: ExecutorService) : this() {
    this.executorService = executorService
  }

  @Synchronized fun executorService(): ExecutorService {
    if (executorService == null) {
      executorService = ThreadPoolExecutor(0, Int.MAX_VALUE, 60, TimeUnit.SECONDS,
          SynchronousQueue(), threadFactory("OkHttp Dispatcher", false))
    }
    return executorService!!
  }

  /**
   * Set a callback to be invoked each time the dispatcher becomes idle (when the number of running
   * calls returns to zero).
   *
   * Note: The time at which a [call][Call] is considered idle is different depending
   * on whether it was run [asynchronously][Call.enqueue] or [synchronously][Call.execute].
   * Asynchronous calls become idle after the [onResponse][Callback.onResponse] or
   * [onFailure][Callback.onFailure] callback has returned. Synchronous calls become idle once
   * [execute()][Call.execute] returns. This means that if you are doing synchronous calls the
   * network layer will not truly be idle until every returned [Response] has been closed.
   */
  @Synchronized fun setIdleCallback(idleCallback: Runnable?) {
    this.idleCallback = idleCallback
  }

  // This lambda conversion is for Kotlin callers expecting a Java SAM (single-abstract-method).
  @JvmName("-deprecated_setIdleCallback")
  inline fun setIdleCallback(crossinline idleCallback: () -> Unit) =
      setIdleCallback(Runnable { idleCallback() })

  internal fun enqueue(call: AsyncCall) {
    synchronized(this) {
      // 添加异步请求到待执行队列
      readyAsyncCalls.add(call)

      // Mutate the AsyncCall so that it shares the AtomicInteger of an existing running call to
      // the same host.
      if (!call.get().forWebSocket) {
        val existingCall = findExistingCallWithHost(call.host())
        if (existingCall != null) call.reuseCallsPerHostFrom(existingCall)
      }
    }
    // 将符合条件的待执行异步请求提升到运行中请求队列中，并开始执行它们
    promoteAndExecute()
  }

  private fun findExistingCallWithHost(host: String): AsyncCall? {
    for (existingCall in runningAsyncCalls) {
      if (existingCall.host() == host) return existingCall
    }
    for (existingCall in readyAsyncCalls) {
      if (existingCall.host() == host) return existingCall
    }
    return null
  }

  /**
   * Cancel all calls currently enqueued or executing. Includes calls executed both
   * [synchronously][Call.execute] and [asynchronously][Call.enqueue].
   */
  @Synchronized fun cancelAll() {
    for (call in readyAsyncCalls) {
      call.get().cancel()
    }
    for (call in runningAsyncCalls) {
      call.get().cancel()
    }
    for (call in runningSyncCalls) {
      call.cancel()
    }
  }

  /**
   * Promotes eligible calls from [readyAsyncCalls] to [runningAsyncCalls] and runs them on the
   * executor service. Must not be called with synchronization because executing calls can call
   * into user code.
   *
   * @return true if the dispatcher is currently running calls.
   */
  private fun promoteAndExecute(): Boolean {
    assert(!Thread.holdsLock(this))
    // 存放符合条件的可执行请求
    val executableCalls = ArrayList<AsyncCall>()
    val isRunning: Boolean
    synchronized(this) {
      val i = readyAsyncCalls.iterator()
      while (i.hasNext()) {
        val asyncCall = i.next()

        // 超出可同时执行的最大请求数
        if (runningAsyncCalls.size >= this.maxRequests) break // Max capacity.
        // 超出对同一主机可同时执行的最大请求数，continue
        if (asyncCall.callsPerHost().get() >= this.maxRequestsPerHost) continue // Host max capacity.
        // 可以被执行，移除待执行队列
        i.remove()
        // 同一主机正在执行的请求数量自增，{@link AtomicInteger} 类型
        asyncCall.callsPerHost().incrementAndGet()
        // 加入可执行列表
        executableCalls.add(asyncCall)
        runningAsyncCalls.add(asyncCall)
      }
      isRunning = runningCallsCount() > 0
    }

    for (i in 0 until executableCalls.size) {
      val asyncCall = executableCalls[i]
      // 轮询执行请求
      asyncCall.executeOn(executorService())
    }

    return isRunning
  }

  /** Used by `Call#execute` to signal it is in-flight.  */
  @Synchronized internal fun executed(call: RealCall) {
    runningSyncCalls.add(call)
  }

  /** Used by `AsyncCall#run` to signal completion.  */
  internal fun finished(call: AsyncCall) {
    call.callsPerHost().decrementAndGet()
    finished(runningAsyncCalls, call)
  }

  /** Used by `Call#execute` to signal completion.  */
  internal fun finished(call: RealCall) {
    finished(runningSyncCalls, call)
  }

  private fun <T> finished(calls: Deque<T>, call: T) {
    val idleCallback: Runnable?
    synchronized(this) {
      if (!calls.remove(call)) throw AssertionError("Call wasn't in-flight!")
      idleCallback = this.idleCallback
    }

    val isRunning = promoteAndExecute()
    // 未发现源码中有为 idleCallback 赋值的地方，暂认为下面代码不会执行
    if (!isRunning && idleCallback != null) {
      idleCallback.run()
    }
  }

  /** Returns a snapshot of the calls currently awaiting execution.  */
  @Synchronized fun queuedCalls(): List<Call> {
    return Collections.unmodifiableList(readyAsyncCalls.map { it.get() })
  }

  /** Returns a snapshot of the calls currently being executed.  */
  @Synchronized fun runningCalls(): List<Call> {
    return Collections.unmodifiableList(runningSyncCalls + runningAsyncCalls.map { it.get() })
  }

  @Synchronized fun queuedCallsCount(): Int = readyAsyncCalls.size

  @Synchronized fun runningCallsCount(): Int = runningAsyncCalls.size + runningSyncCalls.size
}