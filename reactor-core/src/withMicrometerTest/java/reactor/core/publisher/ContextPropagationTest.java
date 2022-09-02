/*
 * Copyright (c) 2022 VMware Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.core.publisher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import io.micrometer.context.ContextRegistry;
import net.bytebuddy.implementation.bytecode.Throw;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;
import org.reactivestreams.Publisher;

import reactor.core.Fuseable;
import reactor.core.Scannable;
import reactor.core.observability.SignalListener;
import reactor.core.observability.SignalListenerFactory;
import reactor.test.ParameterizedTestWithName;
import reactor.test.subscriber.TestSubscriber;
import reactor.test.subscriber.TestSubscriberBuilder;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Simon Baslé
 */
class ContextPropagationTest {

	private static final String KEY1 = "key1";
	private static final String KEY2 = "key2";

	private static final AtomicReference<String> REF1 = new AtomicReference<>();
	private static final AtomicReference<String> REF2 = new AtomicReference<>();

	//NOTE: no way to currently remove accessors from the ContextRegistry, so we recreate one on each test
	private ContextRegistry registry;

	@BeforeEach
	void setup() {
		registry = new ContextRegistry().loadContextAccessors();

		REF1.set("ref1_init");
		REF2.set("ref2_init");

		registry.registerThreadLocalAccessor(
			KEY1, REF1::get, REF1::set, () -> REF1.set(null));

		registry.registerThreadLocalAccessor(
			KEY2, REF2::get, REF2::set, () -> REF2.set(null));
	}

	@Test
	void isContextPropagationAvailable() {
		assertThat(ContextPropagation.isContextPropagationAvailable()).isTrue();
	}


	@Test
	void contextCaptureWithNoPredicateReturnsTheConstantFunction() {
		assertThat(ContextPropagation.contextCapture())
			.as("no predicate nor registry")
			.isSameAs(ContextPropagation.WITH_GLOBAL_REGISTRY_NO_PREDICATE)
			.hasFieldOrPropertyWithValue("registry", ContextRegistry.getInstance());
	}

	@Test
	void contextCaptureWithPredicateReturnsNewFunctionWithGlobalRegistry() {
		Function<Context, Context> test = ContextPropagation.contextCapture(ContextPropagation.PREDICATE_TRUE);

		assertThat(test)
			.as("predicate, no registry")
			.isNotNull()
			.isNotSameAs(ContextPropagation.WITH_GLOBAL_REGISTRY_NO_PREDICATE)
			.isNotSameAs(ContextPropagation.NO_OP)
			// as long as a predicate is supplied, the method creates new instances of the Function
			.isNotSameAs(ContextPropagation.contextCapture(ContextPropagation.PREDICATE_TRUE))
			.isInstanceOfSatisfying(ContextPropagation.ContextCaptureFunction.class, f ->
				assertThat(f.registry).as("function default registry").isSameAs(ContextRegistry.getInstance()));
	}

	@Test
	void fluxApiUsesContextPropagationConstantFunction() {
		Flux<Integer> source = Flux.empty();
		assertThat(source.contextCapture())
			.isInstanceOfSatisfying(FluxContextWrite.class, fcw ->
				assertThat(fcw.doOnContext)
					.as("flux's capture function")
					.isSameAs(ContextPropagation.WITH_GLOBAL_REGISTRY_NO_PREDICATE)
			);
	}

	@Test
	void monoApiUsesContextPropagationConstantFunction() {
		Mono<Integer> source = Mono.empty();
		assertThat(source.contextCapture())
			.isInstanceOfSatisfying(MonoContextWrite.class, fcw ->
				assertThat(fcw.doOnContext)
					.as("mono's capture function")
					.isSameAs(ContextPropagation.WITH_GLOBAL_REGISTRY_NO_PREDICATE)
			);
	}

	@Nested
	class ContextCaptureFunctionTest {

		@Test
		void contextCaptureFunctionWithoutFiltering() {
			ContextPropagation.ContextCaptureFunction test = new ContextPropagation.ContextCaptureFunction(
				ContextPropagation.PREDICATE_TRUE, registry);

			Context ctx = test.apply(Context.empty());
			Map<Object, Object> asMap = new HashMap<>();
			ctx.forEach(asMap::put); //easier to assert

			assertThat(asMap)
				.containsEntry(KEY1, "ref1_init")
				.containsEntry(KEY2, "ref2_init")
				.containsEntry(ContextPropagation.CAPTURED_CONTEXT_MARKER, true)
				.hasSize(3);
		}

		@Test
		void captureWithFiltering() {
			ContextPropagation.ContextCaptureFunction test = new ContextPropagation.ContextCaptureFunction(
				k -> k.toString().equals(KEY2), registry);

			Context ctx = test.apply(Context.empty());
			Map<Object, Object> asMap = new HashMap<>();
			ctx.forEach(asMap::put); //easier to assert

			assertThat(asMap)
				.containsEntry(KEY2, "ref2_init")
				.containsEntry(ContextPropagation.CAPTURED_CONTEXT_MARKER, true)
				.hasSize(2);
		}

		@Test
		void captureFunctionWithNullRegistryUsesGlobalRegistry() {
			ContextPropagation.ContextCaptureFunction test = new ContextPropagation.ContextCaptureFunction(v -> true, null);

			assertThat(test.registry).as("default registry").isSameAs(ContextRegistry.getInstance());
		}
	}

	static private enum Cases {
		NORMAL_NO_MARKER(false, false, false),
		NORMAL_WITH_MARKER(false, false, true),
		CONDITIONAL_NO_MARKER(false, true, false),
		CONDITIONAL_WITH_MARKER(false, true, true),
		FUSED_NO_MARKER(true, false, false),
		FUSED_WITH_MARKER(true, false, true),
		FUSED_CONDITIONAL_NO_MARKER(true, true, false),
		FUSED_CONDITIONAL_WITH_MARKER(true, true, true);

		final boolean fusion;
		final boolean conditional;
		final boolean marker;

		Cases(boolean fusion, boolean conditional, boolean marker) {
			this.fusion = fusion;
			this.conditional = conditional;
			this.marker = marker;
		}
	}

	@Nested
	class ContextRestoreForTap {

		@EnumSource(Cases.class)
		@ParameterizedTestWithName
		void properWrappingForFluxTap(Cases characteristics) {
			SignalListener<String> originalListener = Mockito.mock(SignalListener.class);
			SignalListenerFactory<String, Void> originalFactory = new SignalListenerFactory<String, Void>() {
				@Override
				public Void initializePublisherState(Publisher<? extends String> source) {
					return null;
				}

				@Override
				public SignalListener<String> createListener(Publisher<? extends String> source,
															 ContextView listenerContext, Void publisherContext) {
					return originalListener;
				}
			};

			Publisher<String> tap;
			TestSubscriberBuilder builder = TestSubscriber.builder();
			if (characteristics.fusion) {
				tap = new FluxTapFuseable<>(Flux.empty(), originalFactory);
				builder = builder.requireFusion(Fuseable.ANY);
			}
			else {
				tap = new FluxTap<>(Flux.empty(), originalFactory);
				builder = builder.requireNotFuseable();
			}

			if (characteristics.marker) {
				builder = builder.contextPut(ContextPropagation.CAPTURED_CONTEXT_MARKER, true);
			}

			TestSubscriber<String> testSubscriber;
			if (characteristics.conditional) {
				testSubscriber = builder.buildConditional(v -> true);
			}
			else {
				testSubscriber = builder.build();
			}

			tap.subscribe(testSubscriber);
			Scannable parent = testSubscriber.parents().findFirst().get();

			if (!characteristics.fusion) {
				assertThat(parent).isInstanceOfSatisfying(FluxTap.TapSubscriber.class,
					tapSubscriber -> {
						if (characteristics.marker) {
							assertThat(tapSubscriber.listener).as("listener wrapped")
								.isNotSameAs(originalListener)
								.isInstanceOf(ContextPropagation.ContextRestoreSignalListener.class);
						}
						else {
							assertThat(tapSubscriber.listener)
								.as("listener not wrapped")
								.isSameAs(originalListener);
						}
					});
			}
			else {
				assertThat(parent).isInstanceOfSatisfying(FluxTapFuseable.TapFuseableSubscriber.class,
					tapSubscriber -> {
						if (characteristics.marker) {
							assertThat(tapSubscriber.listener)
								.as("listener wrapped")
								.isNotSameAs(originalListener)
								.isInstanceOf(ContextPropagation.ContextRestoreSignalListener.class);
						}
						else {
							assertThat(tapSubscriber.listener)
								.as("listener not wrapped")
								.isSameAs(originalListener);
						}
					});
			}
		}

		@EnumSource(Cases.class)
		@ParameterizedTestWithName
		void properWrappingForMonoTap(Cases characteristics) {
			SignalListener<String> originalListener = Mockito.mock(SignalListener.class);
			SignalListenerFactory<String, Void> originalFactory = new SignalListenerFactory<String, Void>() {
				@Override
				public Void initializePublisherState(Publisher<? extends String> source) {
					return null;
				}

				@Override
				public SignalListener<String> createListener(Publisher<? extends String> source,
															 ContextView listenerContext, Void publisherContext) {
					return originalListener;
				}
			};

			Mono<String> tap;
			TestSubscriberBuilder builder = TestSubscriber.builder();
			if (characteristics.fusion) {
				tap = new MonoTapFuseable<>(Mono.empty(), originalFactory);
				builder = builder.requireFusion(Fuseable.ANY);
			}
			else {
				tap = new MonoTap<>(Mono.empty(), originalFactory);
				builder = builder.requireNotFuseable();
			}

			if (characteristics.marker) {
				builder = builder.contextPut(ContextPropagation.CAPTURED_CONTEXT_MARKER, true);
			}

			TestSubscriber<String> testSubscriber;
			if (characteristics.conditional) {
				testSubscriber = builder.buildConditional(v -> true);
			}
			else {
				testSubscriber = builder.build();
			}

			tap.subscribe(testSubscriber);
			Scannable parent = testSubscriber.parents().findFirst().get();

			if (!characteristics.fusion) {
				assertThat(parent).isInstanceOfSatisfying(FluxTap.TapSubscriber.class,
					tapSubscriber -> {
						if (characteristics.marker) {
							assertThat(tapSubscriber.listener)
								.as("listener wrapped")
								.isNotSameAs(originalListener)
								.isInstanceOf(ContextPropagation.ContextRestoreSignalListener.class);

						}
						else {
							assertThat(tapSubscriber.listener).as("listener not wrapped").isSameAs(originalListener);
						}
					});
			}
			else {
				assertThat(parent).isInstanceOfSatisfying(FluxTapFuseable.TapFuseableSubscriber.class,
					tapSubscriber -> {
						if (characteristics.marker) {
							assertThat(tapSubscriber.listener)
								.as("listener wrapped")
								.isNotSameAs(originalListener)
								.isInstanceOf(ContextPropagation.ContextRestoreSignalListener.class);
						}
						else {
							assertThat(tapSubscriber.listener).as("listener not wrapped").isSameAs(originalListener);
						}
					});
			}
		}

		@Test
		void threadLocalRestoredInSignalListener() throws InterruptedException {
			REF1.set(null);
			Context context = Context.of(KEY1, "expected");

			ContextPropagation.ContextRestoreSignalListener<Object> listener = new ContextPropagation.ContextRestoreSignalListener<>(Mockito.mock(SignalListener.class), context, registry);
			List<String> list = new ArrayList<>();

			Thread t = new Thread(() -> {
				try {
					listener.doFirst();
					list.add("doFirst: " + REF1.getAndSet(null));

					listener.doOnSubscription();
					list.add("doOnSubscription: " + REF1.getAndSet(null));

					listener.doOnFusion(1);
					list.add("doOnFusion: " + REF1.getAndSet(null));

					listener.doOnFusion(1);
					list.add("doOnFusion: " + REF1.getAndSet(null));

					listener.doOnRequest(1L);
					list.add("doOnRequest: " + REF1.getAndSet(null));

					listener.doOnCancel();
					list.add("doOnCancel: " + REF1.getAndSet(null));

					listener.doOnNext(1);
					list.add("doOnNext: " + REF1.getAndSet(null));

					listener.doOnComplete();
					list.add("doOnComplete: " + REF1.getAndSet(null));

					listener.doOnError(new IllegalStateException("boom"));
					list.add("doOnError: " + REF1.getAndSet(null));

					listener.doAfterComplete();
					list.add("doAfterComplete: " + REF1.getAndSet(null));

					listener.doAfterError(new IllegalStateException("boom"));
					list.add("doAfterError: " + REF1.getAndSet(null));

					listener.doFinally(SignalType.ON_COMPLETE);
					list.add("doFinally: " + REF1.getAndSet(null));

					listener.doOnMalformedOnNext(1);
					list.add("doOnMalformedOnNext: " + REF1.getAndSet(null));

					listener.doOnMalformedOnComplete();
					list.add("doOnMalformedOnComplete: " + REF1.getAndSet(null));

					listener.doOnMalformedOnError(new IllegalStateException("boom"));
					list.add("doOnMalformedOnComplete: " + REF1.getAndSet(null));

					listener.addToContext(Context.empty());
					list.add("addToContext: " + REF1.getAndSet(null));

					listener.handleListenerError(new IllegalStateException("boom"));
					list.add("handleListenerError: " + REF1.getAndSet(null));
				}
				catch (Throwable error) {
					error.printStackTrace();
				}
			});
			t.start();
			t.join();

			assertThat(list).as("extracted TLs")
				.containsExactly(
					"doFirst: expected",
					"doOnSubscription: expected",
					"doOnFusion: expected",
					"doOnFusion: expected",
					"doOnRequest: expected",
					"doOnCancel: expected",
					"doOnNext: expected",
					"doOnComplete: expected",
					"doOnError: expected",
					"doAfterComplete: expected",
					"doAfterError: expected",
					"doFinally: expected",
					"doOnMalformedOnNext: expected",
					"doOnMalformedOnComplete: expected",
					"doOnMalformedOnComplete: expected",
					"addToContext: expected",
					"handleListenerError: expected"
				);
		}

	}

}
