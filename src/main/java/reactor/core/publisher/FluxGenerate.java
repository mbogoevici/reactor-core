/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.core.publisher;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.flow.Receiver;
import reactor.core.state.Completable;
import reactor.core.state.Introspectable;
import reactor.core.subscriber.SubscriberWithContext;
import reactor.core.util.BackpressureUtils;
import reactor.core.util.EmptySubscription;
import reactor.core.util.Exceptions;
import reactor.core.util.ReactiveStateUtils;

/**
 * A Reactive Streams {@link Publisher} factory which callbacks on start, request and shutdown <p>
 * The Publisher will directly forward all the signals passed to the subscribers and complete when onComplete is called.
 * <p> Create such publisher with the provided factory, E.g.:
 * <pre>
 * {@code
 * PublisherFactory.generate((n, sub) -> {
 *  for(int i = 0; i < n; i++){
 *    sub.onNext(i);
 *  }
 * }
 * }
 * </pre>
 *
 * @author Stephane Maldini
 * @since 2.0.2, 2.5
 */
class FluxGenerate<T, C> extends Flux<T> implements Introspectable {

	final           Function<Subscriber<? super T>, C>            contextFactory;
	protected final BiConsumer<Long, SubscriberWithContext<T, C>> requestConsumer;
	protected final Consumer<C>                                   shutdownConsumer;

	protected FluxGenerate(BiConsumer<Long, SubscriberWithContext<T, C>> requestConsumer,
			Function<Subscriber<? super T>, C> contextFactory,
			Consumer<C> shutdownConsumer) {
		this.requestConsumer = requestConsumer;
		this.contextFactory = contextFactory;
		this.shutdownConsumer = shutdownConsumer;
	}

	@Override
	final public void subscribe(final Subscriber<? super T> subscriber) {
		try {
			final C context = contextFactory != null ? contextFactory.apply(subscriber) : null;
			subscriber.onSubscribe(createSubscription(subscriber, context));
		}
		catch (Throwable throwable) {
			Exceptions.throwIfFatal(throwable);
			EmptySubscription.error(subscriber, throwable);
		}
	}

	protected Subscription createSubscription(Subscriber<? super T> subscriber, C context) {
		return new SubscriberProxy<>(this, subscriber, context, requestConsumer, shutdownConsumer);
	}

	static final class FluxForEach<T, C> extends FluxGenerate<T, C> implements Receiver {

		final Consumer<SubscriberWithContext<T, C>> forEachConsumer;

		public FluxForEach(Consumer<SubscriberWithContext<T, C>> forEachConsumer, Function<Subscriber<? super
				T>, C> contextFactory, Consumer<C> shutdownConsumer) {
			super(null, contextFactory, shutdownConsumer);
			this.forEachConsumer = forEachConsumer;
		}

		@Override
		protected Subscription createSubscription(Subscriber<? super T> subscriber, C context) {
			return new SubscriberProxy<>(this,
					subscriber,
					context,
					new ForEachBiConsumer<>(forEachConsumer),
					shutdownConsumer);
		}

		@Override
		public Object upstream() {
			return forEachConsumer;
		}

		@Override
		public String toString() {
			return forEachConsumer.toString();
		}
	}

	final static class ForEachBiConsumer<T, C> implements BiConsumer<Long, SubscriberWithContext<T, C>> {

		private final Consumer<SubscriberWithContext<T, C>> requestConsumer;

		private volatile long pending = 0L;

		private final static AtomicLongFieldUpdater<ForEachBiConsumer> PENDING_UPDATER =
				AtomicLongFieldUpdater.newUpdater(ForEachBiConsumer.class, "pending");

		public ForEachBiConsumer(Consumer<SubscriberWithContext<T, C>> requestConsumer) {
			this.requestConsumer = requestConsumer;
		}

		@Override
		public void accept(Long n, SubscriberWithContext<T, C> sub) {

			if (n == Long.MAX_VALUE) {
				while (!sub.isCancelled()) {
					requestConsumer.accept(sub);
				}
				return;
			}

			if (BackpressureUtils.getAndAddCap(PENDING_UPDATER, this, n) > 0) {
				return;
			}

			long demand = n;
			do {
				long requestCursor = 0L;
				while ((demand == Long.MAX_VALUE || requestCursor++ < demand) && !sub.isCancelled()) {
					requestConsumer.accept(sub);
				}
			}
			while ((demand == Long.MAX_VALUE || (demand =
					PENDING_UPDATER.addAndGet(this, -demand)) > 0L) && !sub.isCancelled());

		}

	}

	final static class RecursiveConsumer<T, C> implements BiConsumer<Long, SubscriberWithContext<T, C>> {

		private final BiConsumer<Long, SubscriberWithContext<T, C>> requestConsumer;

		@SuppressWarnings("unused")
		private volatile int running = 0;

		private final static AtomicIntegerFieldUpdater<RecursiveConsumer> RUNNING =
				AtomicIntegerFieldUpdater.newUpdater(RecursiveConsumer.class, "running");

		@SuppressWarnings("unused")
		private volatile long pending = 0L;

		private final static AtomicLongFieldUpdater<RecursiveConsumer> PENDING_UPDATER =
				AtomicLongFieldUpdater.newUpdater(RecursiveConsumer.class, "pending");

		public RecursiveConsumer(BiConsumer<Long, SubscriberWithContext<T, C>> requestConsumer) {
			this.requestConsumer = requestConsumer;
		}

		@Override
		public void accept(Long n, SubscriberWithContext<T, C> sub) {
			BackpressureUtils.getAndAddCap(PENDING_UPDATER, this, n);
			if (RUNNING.getAndIncrement(this) == 0) {
				int missed = 1;
				long r;
				for (; ; ) {
					if (sub.isCancelled()) {
						return;
					}

					r = PENDING_UPDATER.getAndSet(this, 0L);
					if (r == Long.MAX_VALUE) {
						requestConsumer.accept(Long.MAX_VALUE, sub);
						return;
					}

					if (r != 0L) {
						requestConsumer.accept(r, sub);
					}

					missed = RUNNING.addAndGet(this, -missed);
					if (missed == 0) {
						break;
					}
				}
			}

		}

	}

	private final static class SubscriberProxy<T, C> extends SubscriberWithContext<T, C>
			implements Subscription, Receiver, Completable, Introspectable {

		private final BiConsumer<Long, SubscriberWithContext<T, C>> requestConsumer;

		private final Consumer<C> shutdownConsumer;

		private final Publisher<T> source;

		public SubscriberProxy(Publisher<T> source,
				Subscriber<? super T> subscriber,
				C context,
				BiConsumer<Long, SubscriberWithContext<T, C>> requestConsumer,
				Consumer<C> shutdownConsumer) {
			super(context, subscriber);
			this.source = source;
			this.requestConsumer = requestConsumer;
			this.shutdownConsumer = shutdownConsumer;
		}

		@Override
		public Publisher<T> upstream() {
			return source;
		}

		@Override
		public String getName() {
			return ReactiveStateUtils.getName(requestConsumer);
		}

		@Override
		public void request(long n) {
			if (isCancelled()) {
				return;
			}

			if (BackpressureUtils.checkRequest(n, this)) {
				try {
					requestConsumer.accept(n, this);
				}
				catch (Throwable t) {
					onError(t);
				}
			}
		}

		@Override
		public void cancel() {
			if (TERMINAL_UPDATER.compareAndSet(this, 0, 1)) {
				doShutdown();
			}
		}

		@Override
		public void onError(Throwable t) {
			if (TERMINAL_UPDATER.compareAndSet(this, 0, 1)) {
				doShutdown();
				if (Exceptions.CancelException.class != t.getClass()) {
					subscriber.onError(t);
				}
			}
			else{
				Exceptions.onErrorDropped(t);
			}
		}

		@Override
		public void onComplete() {
			if (TERMINAL_UPDATER.compareAndSet(this, 0, 1)) {
				doShutdown();
				try {
					subscriber.onComplete();
				}
				catch (Throwable t) {
					Exceptions.throwIfFatal(t);
					subscriber.onError(t);
				}
			}
		}

		private void doShutdown() {
			if (shutdownConsumer == null) {
				return;
			}

			try {
				shutdownConsumer.accept(context);
			}
			catch (Throwable t) {
				Exceptions.throwIfFatal(t);
				subscriber.onError(t);
			}
		}

		@Override
		public int getMode() {
			return 0;
		}

		@Override
		public void onSubscribe(Subscription s) {
			throw new UnsupportedOperationException(" the delegate subscriber is already subscribed");
		}

		@Override
		public String toString() {
			return source.toString();
		}

	}
}
