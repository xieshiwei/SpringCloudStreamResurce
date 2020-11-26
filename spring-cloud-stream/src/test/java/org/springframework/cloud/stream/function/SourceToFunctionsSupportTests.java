/*
 * Copyright 2018-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.stream.function;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import reactor.core.publisher.Flux;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.cloud.function.context.PollableBean;
import org.springframework.cloud.stream.annotation.EnableBinding;
import org.springframework.cloud.stream.binder.test.OutputDestination;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.cloud.stream.messaging.Source;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.IntegrationFlows;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.support.GenericMessage;
import org.springframework.util.MimeTypeUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Oleg Zhurakousky
 * @author David Turanski
 * @since 2.1
 */
public class SourceToFunctionsSupportTests {

	@Rule
	public ExpectedException expectedException = ExpectedException.none();

	@Test
	public void testFunctionIsAppliedToExistingMessageSource() {
		try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
				TestChannelBinderConfiguration.getCompleteConfiguration(
						FunctionsConfiguration.class, ExistingMessageSourceConfiguration.class)).web(WebApplicationType.NONE).run(
								"--spring.cloud.stream.function.definition=|toUpperCase",
								"--spring.jmx.enabled=false")) {

			OutputDestination target = context.getBean(OutputDestination.class);
			assertThat(target.receive(1000).getPayload())
					.isEqualTo("HELLO FUNCTION".getBytes(StandardCharsets.UTF_8));
		}
	}

	@Test
	public void testFunctionsAreAppliedToExistingMessageSource() {
		try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
				TestChannelBinderConfiguration.getCompleteConfiguration(
						FunctionsConfiguration.class, ExistingMessageSourceConfiguration.class)).web(WebApplicationType.NONE).run(
								"--spring.cloud.stream.function.definition=|toUpperCase|concatWithSelf",
								"--spring.jmx.enabled=false")) {

			OutputDestination target = context.getBean(OutputDestination.class);
			assertThat(target.receive(1000).getPayload())
					.isEqualTo("HELLO FUNCTION:HELLO FUNCTION".getBytes(StandardCharsets.UTF_8));
		}
	}

	@Test
	public void testFunctionsAreAppliedToExistingMessageSourceReactive() {
		try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
				TestChannelBinderConfiguration.getCompleteConfiguration(
						FunctionsConfiguration.class, ExistingMessageSourceConfiguration.class)).web(WebApplicationType.NONE).run(
								"--spring.cloud.stream.function.definition=|toUpperCaseReactive|concatWithSelf",
								"--spring.jmx.enabled=false")) {

			OutputDestination target = context.getBean(OutputDestination.class);
			assertThat(target.receive(1000).getPayload())
					.isEqualTo("HELLO FUNCTION:HELLO FUNCTION".getBytes(StandardCharsets.UTF_8));
		}
	}

	@Test
	public void testImperativeSupplier() {
		try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
				TestChannelBinderConfiguration.getCompleteConfiguration(
						FunctionsConfiguration.class, SupplierConfiguration.class)).web(WebApplicationType.NONE).run(
								"--spring.cloud.stream.function.definition=number",
								"--spring.jmx.enabled=false")) {

			OutputDestination target = context.getBean(OutputDestination.class);
			String result = new String(target.receive(1000).getPayload(), StandardCharsets.UTF_8);
			assertThat(result).isEqualTo("1");
		}
	}

	@Test
	public void testImperativeSupplierComposedWithFunctions() {
		try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
				TestChannelBinderConfiguration.getCompleteConfiguration(
						FunctionsConfiguration.class, SupplierConfiguration.class)).web(WebApplicationType.NONE).run(
								"--spring.cloud.stream.function.definition=number|toUpperCase|concatWithSelf",
								"--spring.jmx.enabled=false")) {

			OutputDestination target = context.getBean(OutputDestination.class);
			String result = new String(target.receive(1000).getPayload(), StandardCharsets.UTF_8);
			assertThat(result).isEqualTo("1:1");
		}
	}

	@Test
	public void testImperativeSupplierComposedWithMixedFunctions() {
		try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
				TestChannelBinderConfiguration.getCompleteConfiguration(
						FunctionsConfiguration.class, SupplierConfiguration.class)).web(WebApplicationType.NONE).run(
								"--spring.cloud.stream.function.definition=number|toUpperCaseReactive|concatWithSelf",
								"--spring.jmx.enabled=false")) {

			OutputDestination target = context.getBean(OutputDestination.class);
			String result = new String(target.receive(1000).getPayload(), StandardCharsets.UTF_8);
			assertThat(result).isEqualTo("1:1");
		}
	}

	@Test
	public void testReactiveSupplier() {
		try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
				TestChannelBinderConfiguration.getCompleteConfiguration(SupplierConfiguration.class)).web(WebApplicationType.NONE).run(
								"--spring.cloud.stream.function.definition=numberReactive",
								"--spring.jmx.enabled=false")) {

			OutputDestination target = context.getBean(OutputDestination.class);
			String result = new String(target.receive(1000).getPayload(), StandardCharsets.UTF_8);
			assertThat(result).isEqualTo("0");
			result = new String(target.receive(1000).getPayload(), StandardCharsets.UTF_8);
			assertThat(result).isEqualTo("1");
			result = new String(target.receive(1000).getPayload(), StandardCharsets.UTF_8);
			assertThat(result).isEqualTo("2");
		}
	}

	@Test
	public void testReactiveSupplierComposedWithImperativeFunctions() {
		try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
				TestChannelBinderConfiguration.getCompleteConfiguration(FunctionsConfiguration.class,
						SupplierConfiguration.class)).web(WebApplicationType.NONE).run(
								"--spring.cloud.stream.function.definition=numberReactive|toUpperCase|concatWithSelf",
								"--spring.jmx.enabled=false")) {

			OutputDestination target = context.getBean(OutputDestination.class);
			String result = new String(target.receive(1000).getPayload(), StandardCharsets.UTF_8);
			assertThat(result).isEqualTo("0:0");
			result = new String(target.receive(1000).getPayload(), StandardCharsets.UTF_8);
			assertThat(result).isEqualTo("1:1");
			result = new String(target.receive(1000).getPayload(), StandardCharsets.UTF_8);
			assertThat(result).isEqualTo("2:2");
		}
	}

	@Test
	public void testReactiveSupplierComposedWithMixedFunctions() {
		try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
				TestChannelBinderConfiguration.getCompleteConfiguration(FunctionsConfiguration.class,
						SupplierConfiguration.class)).web(WebApplicationType.NONE).run(
								"--spring.cloud.stream.function.definition=numberReactive|concatWithSelf|toUpperCaseReactive",
								"--spring.jmx.enabled=false")) {

			OutputDestination target = context.getBean(OutputDestination.class);
			String result = new String(target.receive(1000).getPayload(), StandardCharsets.UTF_8);
			assertThat(result).isEqualTo("0:0");
			result = new String(target.receive(1000).getPayload(), StandardCharsets.UTF_8);
			assertThat(result).isEqualTo("1:1");
			result = new String(target.receive(1000).getPayload(), StandardCharsets.UTF_8);
			assertThat(result).isEqualTo("2:2");
		}
	}

	@Test
	public void testReactiveSupplierComposedWithMixedFunctions2() {
		try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
				TestChannelBinderConfiguration.getCompleteConfiguration(FunctionsConfiguration.class,
						SupplierConfiguration.class)).web(WebApplicationType.NONE).run(
								"--spring.cloud.stream.function.definition=numberReactive|toUpperCaseReactive|concatWithSelf",
								"--spring.jmx.enabled=false")) {

			OutputDestination target = context.getBean(OutputDestination.class);
			String result = new String(target.receive(1000).getPayload(), StandardCharsets.UTF_8);
			assertThat(result).isEqualTo("0:0");
			result = new String(target.receive(1000).getPayload(), StandardCharsets.UTF_8);
			assertThat(result).isEqualTo("1:1");
			result = new String(target.receive(1000).getPayload(), StandardCharsets.UTF_8);
			assertThat(result).isEqualTo("2:2");
		}
	}

	@Test
	public void testFiniteFluxSupplierMessage() {
		try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
				TestChannelBinderConfiguration.getCompleteConfiguration(FunctionsConfiguration.class,
						MessageFluxSupplierConfiguration.class)).web(WebApplicationType.NONE).run(
								"--spring.cloud.stream.function.definition=messageStreamSupplier",
								"--spring.jmx.enabled=false")) {

			OutputDestination target = context.getBean(OutputDestination.class);

			assertThat(new String(target.receive(2000).getPayload())).isEqualTo("1");
			assertThat(new String(target.receive(2000).getPayload())).isEqualTo("2");
			assertThat(new String(target.receive(2000).getPayload())).isEqualTo("3");
			assertThat(new String(target.receive(2000).getPayload())).isEqualTo("4");
			assertThat(new String(target.receive(2000).getPayload())).isEqualTo("5");
			assertThat(new String(target.receive(2000).getPayload())).isEqualTo("6");
		}
	}

	@Test
	public void testFiniteFluxSupplierSimple() {
		try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
				TestChannelBinderConfiguration.getCompleteConfiguration(FunctionsConfiguration.class,
						SimpleFluxSupplierConfiguration.class)).web(WebApplicationType.NONE).run(
								"--spring.cloud.stream.function.definition=simpleStreamSupplier",
								"--spring.jmx.enabled=false")) {

			OutputDestination target = context.getBean(OutputDestination.class);

			assertThat(new String(target.receive(2000).getPayload())).isEqualTo("1");
			assertThat(new String(target.receive(2000).getPayload())).isEqualTo("2");
			assertThat(new String(target.receive(2000).getPayload())).isEqualTo("3");
			assertThat(new String(target.receive(2000).getPayload())).isEqualTo("4");
			assertThat(new String(target.receive(2000).getPayload())).isEqualTo("5");
			assertThat(new String(target.receive(2000).getPayload())).isEqualTo("6");

			//assertThat(context.getBean("supplierInitializer")).isNotEqualTo(null);
		}
	}

	@Test
	public void testMultipleSuppliers() {
		try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
				TestChannelBinderConfiguration.getCompleteConfiguration(FunctionsConfiguration.class,
						MultipleSupplierConfiguration.class)).web(WebApplicationType.NONE).run(
								"--spring.cloud.function.definition=supplier1;supplier2",
								"--spring.jmx.enabled=false"
//								"--spring.cloud.stream.function.bindings.supplier1-out-0=output1",
//								"--spring.cloud.stream.function.bindings.supplier2-out-0=output2"
								)) {

			OutputDestination target = context.getBean(OutputDestination.class);

//			assertThat(new String(target.receive(2000).getPayload())).isEqualTo("1");
//			assertThat(new String(target.receive(2000).getPayload())).isEqualTo("2");
//			assertThat(new String(target.receive(2000).getPayload())).isEqualTo("3");
//			assertThat(new String(target.receive(2000).getPayload())).isEqualTo("4");
//			assertThat(new String(target.receive(2000).getPayload())).isEqualTo("5");
//			assertThat(new String(target.receive(2000).getPayload())).isEqualTo("6");
//
//			assertThat(context.getBean("supplierInitializer")).isNotEqualTo(null);
		}
	}

	@EnableAutoConfiguration
	public static class MessageFluxSupplierConfiguration {
		AtomicInteger counter = new AtomicInteger();

		@PollableBean(splittable = true)
		public Supplier<Flux<Message<?>>> messageStreamSupplier() {
			return () -> {
				Message<String> m1 = new GenericMessage<>(String.valueOf(counter.incrementAndGet()));
				Message<String> m2 = new GenericMessage<>(String.valueOf(counter.incrementAndGet()));
				Message<String> m3 = new GenericMessage<>(String.valueOf(counter.incrementAndGet()));
				return Flux.just(m1, m2, m3);
			};
		}
	}

	@EnableAutoConfiguration
	public static class MultipleSupplierConfiguration {

		@Bean
		public Supplier<String> supplier1() {
			return () -> "supplier1";
		}

		@Bean
		public Supplier<String> supplier2() {
			return () -> "supplier2";
		}
	}

	@EnableAutoConfiguration
	public static class SimpleFluxSupplierConfiguration {
		AtomicInteger counter = new AtomicInteger();

		@PollableBean(splittable = true)
		public Supplier<Flux<String>> simpleStreamSupplier() {
			return () -> {
				return Flux.just(String.valueOf(counter.incrementAndGet()),
						String.valueOf(counter.incrementAndGet()),
						String.valueOf(counter.incrementAndGet()));
			};
		}
	}

	@EnableAutoConfiguration
	public static class SupplierConfiguration {

		AtomicInteger counter = new AtomicInteger();

		@Bean
		public Supplier<String> number() {
			return () -> String.valueOf(this.counter.incrementAndGet());
		}

		@Bean
		public Supplier<Flux<String>> numberReactive() {
			return () -> Flux.create(emitter -> {
				for (int i = 0; i < 3; i++) {
					emitter.next(String.valueOf(i));
				}
			});
		}


		@Bean
		public Function<Flux<String>, Flux<String>> multiplyByTwo() {
			return x -> x.map(i -> String.valueOf(Integer.valueOf(i) * 2));
		}

	}

	@EnableAutoConfiguration
	public static class FunctionsConfiguration {

		@Bean
		public Function<String, String> toUpperCase() {
			return String::toUpperCase;
		}

		@Bean
		public Function<Flux<String>, Flux<String>> toUpperCaseReactive() {
			return flux -> flux.map(String::toUpperCase);
		}

		@Bean
		public Function<String, String> concatWithSelf() {
			return x -> x + ":" + x;
		}

	}

	/**
	 * This configuration essentially emulates our existing app-starters for Sources and
	 * essentially demonstrates how a function(s) could be applied to an existing source
	 * via {@link IntegrationFlowFunctionSupport} class.
	 */
	@EnableBinding(Source.class)
	public static class ExistingMessageSourceConfiguration {

		@Bean
		public IntegrationFlow messageSourceFlow(Source source) {
			Supplier<Message<String>> messageSource = () -> MessageBuilder
					.withPayload("hello function")
					.setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN)
					.build();
			return IntegrationFlows.fromSupplier(messageSource).channel(source.output()).get();
		}

	}
}
