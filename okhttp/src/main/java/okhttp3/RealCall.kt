/*
 * Copyright (C) 2014 Square, Inc.
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

import okhttp3.internal.cache.CacheInterceptor
import okhttp3.internal.closeQuietly
import okhttp3.internal.connection.ConnectInterceptor
import okhttp3.internal.connection.Transmitter
import okhttp3.internal.http.BridgeInterceptor
import okhttp3.internal.http.CallServerInterceptor
import okhttp3.internal.http.RealInterceptorChain
import okhttp3.internal.http.RetryAndFollowUpInterceptor
import okhttp3.internal.platform.Platform
import okhttp3.internal.platform.Platform.Companion.INFO
import okhttp3.internal.threadName
import okio.Timeout
import java.io.IOException
import java.io.InterruptedIOException
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

internal class RealCall private constructor(
  val client: OkHttpClient,
  /** The application's original request unadulterated by redirects or auth headers.  */
  val originalRequest: Request,
  val forWebSocket: Boolean
) : Call {
  /**
   * There is a cycle between the [Call] and [Transmitter] that makes this awkward.
   * This is set after immediately after creating the call instance.
   */
  private lateinit var transmitter: Transmitter

  // Guarded by this.
  var executed: Boolean = false

  @Synchronized override fun isExecuted(): Boolean = executed

  override fun isCanceled(): Boolean = transmitter.isCanceled

  override fun request(): Request = originalRequest

  override fun execute(): Response {
    synchronized(this) {
      check(!executed) { "Already Executed" }
      executed = true
    }
    transmitter.timeoutEnter()
    transmitter.callStart()
    try {
      client.dispatcher().executed(this)
      return getResponseWithInterceptorChain()
    } finally {
      client.dispatcher().finished(this)
    }
  }

  override fun enqueue(responseCallback: Callback) {
    synchronized(this) {
      check(!executed) { "Already Executed" }
      executed = true
    }
    transmitter.callStart()
    // 通过调度器的 enqueue 实现异步操作
    // AsyncCall 实现了 Runnable，用来被调度器中的 ExecutorService 进行异步调用
    client.dispatcher().enqueue(AsyncCall(responseCallback))
  }

  override fun cancel() {
    transmitter.cancel()
  }

  override fun timeout(): Timeout = transmitter.timeout()

  @SuppressWarnings("CloneDoesntCallSuperClone") // We are a final type & this saves clearing state.
  override fun clone(): RealCall {
    return newRealCall(client, originalRequest, forWebSocket)
  }

  internal inner class AsyncCall(
    private val responseCallback: Callback
  ) : Runnable {
    @Volatile private var callsPerHost = AtomicInteger(0)

    fun callsPerHost(): AtomicInteger = callsPerHost

    fun reuseCallsPerHostFrom(other: AsyncCall) {
      this.callsPerHost = other.callsPerHost
    }

    fun host(): String = originalRequest.url().host

    fun request(): Request = originalRequest

    fun get(): RealCall = this@RealCall

    /**
     * Attempt to enqueue this async call on [executorService]. This will attempt to clean up
     * if the executor has been shut down by reporting the call as failed.
     */
    fun executeOn(executorService: ExecutorService) {
      assert(!Thread.holdsLock(client.dispatcher()))
      var success = false
      try {
        // 线程池执行 runnable
        executorService.execute(this)
        success = true
      } catch (e: RejectedExecutionException) {
        val ioException = InterruptedIOException("executor rejected")
        ioException.initCause(e)
        transmitter.noMoreExchanges(ioException)
        responseCallback.onFailure(this@RealCall, ioException)
      } finally {
        if (!success) {
          // 请求执行失败后不应再被执行，将请求移出异步请求队列
          client.dispatcher().finished(this) // This call is no longer running!
        }
      }
    }

    override fun run() {
      threadName("OkHttp ${redactedUrl()}") {
        var signalledCallback = false
        transmitter.timeoutEnter()
        try {
          // 调用 {@link #getResponseWithInterceptorChain()} 执行请求，获取 response
          val response = getResponseWithInterceptorChain()
          signalledCallback = true
          // 请求成功回调
          responseCallback.onResponse(this@RealCall, response)
        } catch (e: IOException) {
          if (signalledCallback) {
            // Do not signal the callback twice!
            Platform.get().log(INFO, "Callback failure for ${toLoggableString()}", e)
          } else {
            // 请求失败回调
            responseCallback.onFailure(this@RealCall, e)
          }
        } finally {
          // 请求结束，将请求移出异步请求队列
          client.dispatcher().finished(this)
        }
      }
    }
  }

  /**
   * Returns a string that describes this call. Doesn't include a full URL as that might contain
   * sensitive information.
   */
  fun toLoggableString(): String {
    return ((if (isCanceled()) "canceled " else "") +
        (if (forWebSocket) "web socket" else "call") +
        " to " + redactedUrl())
  }

  fun redactedUrl(): String = originalRequest.url().redact()

  @Throws(IOException::class)
  fun getResponseWithInterceptorChain(): Response {
    // Build a full stack of interceptors.
    val interceptors = ArrayList<Interceptor>()
    interceptors.addAll(client.interceptors())
    // 失败重试与重定向
    interceptors.add(RetryAndFollowUpInterceptor(client))
    // 负责把用户构造的请求转换为发送到服务器的请求，把服务器返回的响应转换为用户友好的响应
    interceptors.add(BridgeInterceptor(client.cookieJar()))
    // 读取缓存直接返回、更新缓存
    interceptors.add(CacheInterceptor(client.internalCache()))
    // 与服务器建立连接
    interceptors.add(ConnectInterceptor(client))
    if (!forWebSocket) {
      interceptors.addAll(client.networkInterceptors())
    }
    // 向服务器发送请求，从服务器读取响应数据
    interceptors.add(CallServerInterceptor(forWebSocket))

    // 拦截器链
    val chain = RealInterceptorChain(interceptors, transmitter, null, 0,
        originalRequest, this, client.connectTimeoutMillis(),
        client.readTimeoutMillis(), client.writeTimeoutMillis())

    var calledNoMoreExchanges = false
    try {
      // 拦截器链开始执行
      val response = chain.proceed(originalRequest)
      if (transmitter.isCanceled) {
        response.closeQuietly()
        throw IOException("Canceled")
      }
      return response
    } catch (e: IOException) {
      calledNoMoreExchanges = true
      throw transmitter.noMoreExchanges(e) as Throwable
    } finally {
      if (!calledNoMoreExchanges) {
        transmitter.noMoreExchanges(null)
      }
    }
  }

  companion object {
    /**
     * @param forWebSocket 不考虑 WebSocket，默认为 false
     */
    fun newRealCall(
      client: OkHttpClient,
      originalRequest: Request,
      forWebSocket: Boolean
    ): RealCall {
      // Safely publish the Call instance to the EventListener.
      return RealCall(client, originalRequest, forWebSocket).apply {
        transmitter = Transmitter(client, this)
      }
    }
  }
}