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

import java.util.Objects;
import java.util.function.Function;

import org.reactivestreams.Processor;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.flow.Producer;
import reactor.core.flow.Receiver;
import reactor.core.scheduler.Scheduler;
import reactor.core.state.Backpressurable;
import reactor.core.state.Completable;
import reactor.core.subscriber.BaseSubscriber;
import reactor.core.subscriber.SignalEmitter;
import reactor.core.subscriber.Subscribers;
import reactor.core.util.BackpressureUtils;
import reactor.core.util.EmptySubscription;
import reactor.core.util.Exceptions;

/**
 * A base processor that expose {@link Flux} API for {@link Processor}.
 *
 * Factories available allow arbitrary {@link FluxProcessor} creation from blackboxed and external reactive components.
 *
 * @author Stephane Maldini
 * @since 2.0.2, 2.5
 */
public abstract class FluxProcessor<IN, OUT> extends Flux<OUT>
		implements Processor<IN, OUT>, Backpressurable, Receiver, Completable, BaseSubscriber<IN> {

	/**
	 * Blackbox a given
	 * {@link Subscriber} receiving type with a transforming function returning the producing side {@link Publisher}.
	 *
	 * <pre>
	 * {@code
	 *   Processor<String, String> asyncLowerCase =
	 *      FluxProcessor.blackbox(TopicProcessor.create(), input -> input.map(String::toLowerCase));
	 * }
	 *</pre>
	 *
	 * @param input an input {@link Subscriber}
	 * @param  blackboxFunction the
	 * {@link Function} given the input subscriber to compose on and return {@link Publisher}
	 * @param <IN> the reified received type
	 * @param <OUT> the reified produced type
	 *
	 * @return a blackboxed chain as {@link FluxProcessor}
	 */
	public static <IN, OUT, E extends Subscriber<IN>> FluxProcessor<IN, OUT> blackbox(
			final E input,
			final Function<E, ? extends Publisher<OUT>> blackboxFunction) {
		return create(input, blackboxFunction.apply(input));
	}

	/**
	 * Blackbox an arbitrary {@link Flux} operation chain into a {@link FluxProcessor} that can be subscribed once
	 * only.
	 * <p>
	 * <pre>
	 * {@code
	 *  Processor<String, Integer> stringToInt =
	 *      FluxProcessor.blackbox(input -> input.map(Integer::parseString));
	 * }
	 *</pre>
	 *
	 * @param blackboxFunction the {@link Function} given a {@link Flux} to compose on and return {@link Publisher}
	 * @param <IN> the reified received type
	 * @param <OUT> the reified produced type
	 *
	 * @return a blackboxed chain as {@link FluxProcessor}
	 */
	public static <IN, OUT> FluxProcessor<IN, OUT> blackbox(final Function<Flux<IN>, ? extends Publisher<OUT>> blackboxFunction) {
		FluxPassthrough<IN> passthrough = new FluxPassthrough<>();
		return create(passthrough, blackboxFunction.apply(passthrough));
	}

	/**
	 * Create a passthrough {@link FluxProcessor} relay blocking when overrun.
	 * <p>
	 * It will use a deferred blocking {@link SignalEmitter} and pass it to {@link #onSubscribe(Subscription)}.
	 * Multiple producer can share the returned reference IF and only IF they don't publish concurrently. In this
	 * very case, implementor must take care of using a multiproducer capable receiver downstream e.g.
	 * {@link TopicProcessor#share()} or {@link WorkQueueProcessor#share()}.
	 *
	 * @return a new {@link FluxProcessor}
	 */
	public static <IN> FluxProcessor<IN, IN> blocking() {
		FluxPassthrough<IN> passthrough = new FluxPassthrough<>();
		return create(SignalEmitter.blocking(passthrough), passthrough);
	}

	/**
	 * Transform a receiving {@link Subscriber} and a producing {@link Publisher} in a logical {@link FluxProcessor}.
	 * The link between the passed upstream and returned downstream will not be created automatically, e.g. not
	 * subscribed together. A {@link Processor} might choose to have orthogonal sequence input and output.
	 *
	 * @param <IN> the receiving type
	 * @param <OUT> the producing type
	 *
	 * @return a new blackboxed {@link FluxProcessor}
	 */
	public static <IN, OUT> FluxProcessor<IN, OUT> create(final Subscriber<IN> upstream, final Publisher<OUT> downstream) {
		return new DelegateProcessor<>(downstream, upstream);
	}

	Subscription upstreamSubscription;

	protected FluxProcessor() {
	}

	@Override
	public FluxProcessor<IN, OUT> connect() {
		onSubscribe(EmptySubscription.INSTANCE);
		return this;
	}

	@Override
	public void onSubscribe(final Subscription s) {
		if (BackpressureUtils.validate(upstreamSubscription, s)) {
			this.upstreamSubscription = s;
			try {
				doOnSubscribe(s);
			}
			catch (Throwable t) {
				Exceptions.throwIfFatal(t);
				s.cancel();
				onError(t);
			}
		}
	}

	protected void doOnSubscribe(Subscription s) {
		//IGNORE
	}

	@Override
	public long getCapacity() {
		return Long.MAX_VALUE;
	}

	@Override
	public void subscribe(Subscriber<? super OUT> s) {
		if (s == null) {
			throw Exceptions.argumentIsNullException();
		}
	}

	protected void cancel(Subscription subscription) {
		if (subscription != EmptySubscription.INSTANCE) {
			subscription.cancel();
		}
	}

	@Override
	public Subscription upstream() {
		return upstreamSubscription;
	}

	@Override
	public int getMode() {
		return 0;
	}

	final static class DelegateProcessor<IN, OUT> extends FluxProcessor<IN, OUT>
			implements Producer, Backpressurable {

		private final Publisher<OUT> downstream;
		private final Subscriber<IN> upstream;

		public DelegateProcessor(Publisher<OUT> downstream, Subscriber<IN> upstream) {
			this.downstream = Objects.requireNonNull(downstream, "Downstream must not be null");
			this.upstream = Objects.requireNonNull(upstream, "Upstream must not be null");
		}

		@Override
		public Subscriber<? super IN> downstream() {
			return upstream;
		}

		@Override
		public long getCapacity() {
			return Backpressurable.class.isAssignableFrom(upstream.getClass()) ?
					((Backpressurable) upstream).getCapacity() :
					Long.MAX_VALUE;
		}

		@Override
		public void onComplete() {
			upstream.onComplete();
		}

		@Override
		public void onError(Throwable t) {
			upstream.onError(t);
		}

		@Override
		public void onNext(IN in) {
			upstream.onNext(in);
		}

		@Override
		public void onSubscribe(Subscription s) {
			upstream.onSubscribe(s);
		}

		@Override
		public void subscribe(Subscriber<? super OUT> s) {
			if(s == null)
				throw Exceptions.argumentIsNullException();
			downstream.subscribe(s);
		}
	}
}
