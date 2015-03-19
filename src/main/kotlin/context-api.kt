/*
 * Copyright (c) 2014-2015 Mark Platvoet<mplatvoet@gmail.com>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package nl.mplatvoet.komponents.kovenant

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger


public object Promises {
    val configuration: Context
        get() = mutableContext.get()!!

    private var mutableContext = AtomicReference(ThreadSafeContext())

    public fun configure(body: MutableContext.() -> Unit) {
        //a copy-on-write strategy is used, but in order to maintain the lazy loading mechanism
        //keeping track of what the developer actually altered is needed, otherwise
        //everything gets initialized during configuration
        val trackingContext = TrackingContext(mutableContext.get()!!)
        trackingContext.body()

        do {
            val current = mutableContext.get()!!
            val newConfig = current.copy()
            trackingContext.applyChanged(newConfig)
        } while (!mutableContext.compareAndSet(current, newConfig))
    }

    private class ThreadSafeContext() : MutableContext {

        private val dispatchingErrorDelegate = ThreadSafeLazyVar<(Exception) -> Unit> {
            {e: Exception -> throw e }
        }
        override var dispatchingError: (Exception) -> Unit by dispatchingErrorDelegate

        private val multipleCompletionDelegate = ThreadSafeLazyVar<(Any, Any) -> Unit> {
            {curVal: Any, newVal: Any -> throw IllegalStateException("Value[$curVal] is set, can't override with new value[$newVal]") }
        }
        override var multipleCompletion: (curVal: Any, newVal: Any) -> Unit by multipleCompletionDelegate


        private val executorDelegate: ThreadSafeLazyVar<(() -> Unit) -> Unit> = ThreadSafeLazyVar {
            val count = AtomicInteger(0)
            val executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors(), {
                val thread = Thread(it)
                thread.setDaemon(true)
                thread.setName("komponents-promises-${count.incrementAndGet()}")
                thread
            })

            Runtime.getRuntime().addShutdownHook(Thread() {
                executorService.shutdown()
                executorService.awaitTermination(60, TimeUnit.SECONDS)
            });
            {func: () -> Unit -> executorService.execute { func() } }
        }
        //TODO Make these distinct
        override var dispatchExecutor: (() -> Unit) -> Unit by executorDelegate
        override var workExecutor: (() -> Unit) -> Unit by executorDelegate

        fun copy(): ThreadSafeContext {
            val copy = ThreadSafeContext()
            if (dispatchingErrorDelegate.initialized) copy.dispatchingError = dispatchingError
            if (executorDelegate.initialized) copy.dispatchExecutor = dispatchExecutor
            if (multipleCompletionDelegate.initialized) copy.multipleCompletion = multipleCompletion
            return copy
        }
    }

    private class TrackingContext(private val currentConfig: Context) : MutableContext {
        private val executorDelegate = TrackChangesVar { currentConfig.dispatchExecutor }
        //TODO make these distinct
        override var dispatchExecutor: (() -> Unit) -> Unit by executorDelegate
        override var workExecutor: (() -> Unit) -> Unit by executorDelegate

        private val dispatchingErrorDelegate = TrackChangesVar { currentConfig.dispatchingError }
        override var dispatchingError: (Exception) -> Unit by dispatchingErrorDelegate

        private val multipleCompletionDelegate = TrackChangesVar { currentConfig.multipleCompletion }
        override var multipleCompletion: (curVal: Any, newVal: Any) -> Unit by multipleCompletionDelegate

        fun applyChanged(config: MutableContext) {
            if (executorDelegate.written)
                config.dispatchExecutor = dispatchExecutor

            if (dispatchingErrorDelegate.written)
                config.dispatchingError = dispatchingError
        }
    }
}

public trait Context {
    val dispatchExecutor: (() -> Unit) -> Unit
    val workExecutor: (() -> Unit) -> Unit
    val dispatchingError: (Exception) -> Unit
    val multipleCompletion: (curVal: Any, newVal: Any) -> Unit
}

public trait MutableContext : Context {
    override var dispatchExecutor: (() -> Unit) -> Unit
    override var workExecutor: (() -> Unit) -> Unit
    override var dispatchingError: (Exception) -> Unit
    override var multipleCompletion: (curVal: Any, newVal: Any) -> Unit
}