/*
 * Copyright 2018-2020 the original author or authors.
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

import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.util.function.Tuples;


import org.springframework.beans.BeansException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.function.context.FunctionCatalog;
import org.springframework.cloud.function.context.FunctionProperties;
import org.springframework.cloud.function.context.FunctionRegistry;
import org.springframework.cloud.function.context.PollableBean;
import org.springframework.cloud.function.context.catalog.FunctionInspector;
import org.springframework.cloud.function.context.catalog.FunctionTypeUtils;
import org.springframework.cloud.function.context.catalog.SimpleFunctionRegistry.FunctionInvocationWrapper;
import org.springframework.cloud.function.context.config.ContextFunctionCatalogAutoConfiguration;
import org.springframework.cloud.function.context.config.FunctionContextUtils;
import org.springframework.cloud.function.context.config.RoutingFunction;
import org.springframework.cloud.stream.annotation.EnableBinding;
import org.springframework.cloud.stream.binder.BindingCreatedEvent;
import org.springframework.cloud.stream.binder.ConsumerProperties;
import org.springframework.cloud.stream.binder.ProducerProperties;
import org.springframework.cloud.stream.binding.BindableProxyFactory;
import org.springframework.cloud.stream.binding.BinderAwareChannelResolver.NewDestinationBindingCallback;
import org.springframework.cloud.stream.config.BinderFactoryAutoConfiguration;
import org.springframework.cloud.stream.config.BindingBeansRegistrar;
import org.springframework.cloud.stream.config.BindingProperties;
import org.springframework.cloud.stream.config.BindingServiceConfiguration;
import org.springframework.cloud.stream.config.BindingServiceProperties;
import org.springframework.cloud.stream.messaging.DirectWithAttributesChannel;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.env.Environment;
import org.springframework.core.type.MethodMetadata;
import org.springframework.integration.channel.AbstractMessageChannel;
import org.springframework.integration.channel.AbstractSubscribableChannel;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.IntegrationFlowBuilder;
import org.springframework.integration.dsl.IntegrationFlows;
import org.springframework.integration.handler.ServiceActivatingHandler;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.integration.util.IntegrationReactiveUtils;
import org.springframework.lang.Nullable;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.SubscribableChannel;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

/**
 * @author Oleg Zhurakousky
 * @author David Turanski
 * @author Ilayaperumal Gopinathan
 * @since 2.1
 */
@Configuration
@EnableConfigurationProperties(StreamFunctionProperties.class)
@Import({ BindingBeansRegistrar.class, BinderFactoryAutoConfiguration.class })
@AutoConfigureBefore(BindingServiceConfiguration.class)
@AutoConfigureAfter(ContextFunctionCatalogAutoConfiguration.class)
@ConditionalOnBean(FunctionRegistry.class)
public class FunctionConfiguration {

	private final static String SOURCE_PROPERY = "spring.cloud.stream.source";

	@Bean
	public StreamBridge streamBridgeUtils(FunctionCatalog functionCatalog, FunctionRegistry functionRegistry,
			BindingServiceProperties bindingServiceProperties, ConfigurableApplicationContext applicationContext,
			@Nullable NewDestinationBindingCallback callback) {
		return new StreamBridge(functionCatalog, functionRegistry, bindingServiceProperties, applicationContext, callback);
	}

	@Bean
	public InitializingBean functionBindingRegistrar(Environment environment, FunctionCatalog functionCatalog,
			StreamFunctionProperties streamFunctionProperties) {
		return new FunctionBindingRegistrar(functionCatalog, streamFunctionProperties);
	}

	@Bean
	public BeanFactoryPostProcessor po(Environment environment) {
		return new PollableSourceRegistrar(environment);
	}

	@Bean
	public InitializingBean functionInitializer(FunctionCatalog functionCatalog, FunctionInspector functionInspector,
			StreamFunctionProperties functionProperties, @Nullable BindableProxyFactory[] bindableProxyFactories,
			BindingServiceProperties serviceProperties, ConfigurableApplicationContext applicationContext,
			FunctionBindingRegistrar bindingHolder, StreamBridge streamBridge) {

		boolean shouldCreateInitializer = applicationContext.containsBean("output")
				|| ObjectUtils.isEmpty(applicationContext.getBeanNamesForAnnotation(EnableBinding.class));

		return shouldCreateInitializer
				? new FunctionToDestinationBinder(functionCatalog, functionProperties,
						serviceProperties, streamBridge)
						: null;
	}

	/*
	 * Binding initializer responsible only for Suppliers
	 */
	@Bean
	InitializingBean supplierInitializer(FunctionCatalog functionCatalog, StreamFunctionProperties functionProperties,
			GenericApplicationContext context, BindingServiceProperties serviceProperties,
			@Nullable BindableFunctionProxyFactory[] proxyFactories, StreamBridge streamBridge,
			TaskScheduler taskScheduler) {

		if (!ObjectUtils.isEmpty(context.getBeanNamesForAnnotation(EnableBinding.class)) || proxyFactories == null) {
			return null;
		}

		return new InitializingBean() {

			@SuppressWarnings("rawtypes")
			@Override
			public void afterPropertiesSet() throws Exception {
				for (BindableFunctionProxyFactory proxyFactory : proxyFactories) {
					FunctionInvocationWrapper functionWrapper = functionCatalog.lookup(proxyFactory.getFunctionDefinition());
					if (functionWrapper != null && functionWrapper.isSupplier()) {
						// gather output content types
						List<String> contentTypes = new ArrayList<String>();
						Assert.isTrue(proxyFactory.getOutputs().size() == 1, "Supplier with multiple outputs is not supported at the moment.");
						String outputName  = proxyFactory.getOutputs().iterator().next();

						BindingProperties bindingProperties = serviceProperties.getBindingProperties(outputName);
						ProducerProperties producerProperties = bindingProperties.getProducer();
						if (!(bindingProperties.getProducer() != null && producerProperties.isUseNativeEncoding())) {
							contentTypes.add(bindingProperties.getContentType());
						}

						// see https://github.com/spring-cloud/spring-cloud-stream/issues/2027
						String functionDefinition = proxyFactory.getFunctionDefinition();
						String[] functionNames = StringUtils.delimitedListToStringArray(functionDefinition.replaceAll(",", "|").trim(), "|");

						Function supplier = null;
						Function function = null;
						if (!ObjectUtils.isEmpty(functionNames) && functionNames.length > 1) {
							String supplierName = functionNames[0];
							String remainingFunctionDefinition = StringUtils
									.arrayToCommaDelimitedString(Arrays.copyOfRange(functionNames, 1, functionNames.length));

							supplier = functionCatalog.lookup(supplierName);
							function = functionCatalog.lookup(remainingFunctionDefinition, contentTypes.toArray(new String[0]));

							if (!((FunctionInvocationWrapper) supplier).isOutputTypePublisher() &&
									((FunctionInvocationWrapper) function).isInputTypePublisher()) {
								functionWrapper = null;
							}
							else {
								functionWrapper = functionCatalog.lookup(proxyFactory.getFunctionDefinition(), contentTypes.toArray(new String[0]));
							}
						}
						else {
							functionWrapper = functionCatalog.lookup(proxyFactory.getFunctionDefinition(), contentTypes.toArray(new String[0]));
						}

						Publisher<Object> beginPublishingTrigger = setupBindingTrigger(context);

						if (!functionProperties.isComposeFrom() && !functionProperties.isComposeTo()) {
							String integrationFlowName = proxyFactory.getFunctionDefinition() + "_integrationflow";
							PollableBean pollable = extractPollableAnnotation(functionProperties, context, proxyFactory);

							if (functionWrapper != null) {
								Type functionType = functionWrapper.getFunctionType();
								IntegrationFlow integrationFlow = integrationFlowFromProvidedSupplier(new PartitionAwareFunctionWrapper(functionWrapper, context, producerProperties),
										beginPublishingTrigger, pollable, context, taskScheduler, functionType)
										.route(Message.class, message -> {
											if (message.getHeaders().get("spring.cloud.stream.sendto.destination") != null) {
												String destinationName = (String) message.getHeaders().get("spring.cloud.stream.sendto.destination");
												return streamBridge.resolveDestination(destinationName, producerProperties);
											}
											return outputName;
										}).get();
								IntegrationFlow postProcessedFlow = (IntegrationFlow) context.getAutowireCapableBeanFactory()
										.applyBeanPostProcessorsBeforeInitialization(integrationFlow, integrationFlowName);
								context.registerBean(integrationFlowName, IntegrationFlow.class, () -> postProcessedFlow);
							}
							else {
								Type functionType = ((FunctionInvocationWrapper) supplier).getFunctionType();
								IntegrationFlow integrationFlow = integrationFlowFromProvidedSupplier(new PartitionAwareFunctionWrapper((FunctionInvocationWrapper) supplier, context, producerProperties),
										beginPublishingTrigger, pollable, context, taskScheduler, functionType)
										.channel(c -> c.direct())
										.fluxTransform((Function<? super Flux<Message<Object>>, ? extends Publisher<Object>>) function)
										.route(Message.class, message -> {
											if (message.getHeaders().get("spring.cloud.stream.sendto.destination") != null) {
												String destinationName = (String) message.getHeaders().get("spring.cloud.stream.sendto.destination");
												return streamBridge.resolveDestination(destinationName, producerProperties);
											}
											return outputName;
										})
										.get();
								IntegrationFlow postProcessedFlow = (IntegrationFlow) context.getAutowireCapableBeanFactory()
										.applyBeanPostProcessorsBeforeInitialization(integrationFlow, integrationFlowName);
								context.registerBean(integrationFlowName, IntegrationFlow.class, () -> postProcessedFlow);
							}
						}
					}
				}
			}
		};
	}


	/*
	 * Creates a publishing trigger to ensure Supplier does not begin publishing until binding is created
	 */
	private Publisher<Object> setupBindingTrigger(GenericApplicationContext context) {
		AtomicReference<MonoSink<Object>> triggerRef = new AtomicReference<>();
		Publisher<Object> beginPublishingTrigger = Mono.create(emmiter -> {
			triggerRef.set(emmiter);
		});
		context.addApplicationListener(event -> {
			if (event instanceof BindingCreatedEvent) {
				if (triggerRef.get() != null) {
					triggerRef.get().success();
				}
			}
		});
		return beginPublishingTrigger;
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private IntegrationFlowBuilder integrationFlowFromProvidedSupplier(Supplier<?> supplier,
			Publisher<Object> beginPublishingTrigger, PollableBean pollable, GenericApplicationContext context,
			TaskScheduler taskScheduler, Type functionType) {

		IntegrationFlowBuilder integrationFlowBuilder;

		boolean splittable = pollable != null
				&& (boolean) AnnotationUtils.getAnnotationAttributes(pollable).get("splittable");
		boolean reactive = FunctionTypeUtils.isPublisher(FunctionTypeUtils.getOutputType(functionType));

		if (pollable == null && reactive) {
			Publisher publisher = (Publisher) supplier.get();
			publisher = publisher instanceof Mono
					? ((Mono) publisher).delaySubscription(beginPublishingTrigger).map(this::wrapToMessageIfNecessary)
					: ((Flux) publisher).delaySubscription(beginPublishingTrigger).map(this::wrapToMessageIfNecessary);

			integrationFlowBuilder = IntegrationFlows.from(publisher);

			// see https://github.com/spring-cloud/spring-cloud-stream/issues/1863 for details about the following code
			taskScheduler.schedule(() -> { }, Instant.now()); // will keep AC alive
		}
		else { // implies pollable
			integrationFlowBuilder = IntegrationFlows.fromSupplier(supplier);
			//only apply the PollableBean attributes if this is a reactive function.
			if (splittable && reactive) {
				integrationFlowBuilder = integrationFlowBuilder.split();
			}
		}

		return integrationFlowBuilder;
	}

	private PollableBean extractPollableAnnotation(StreamFunctionProperties functionProperties, GenericApplicationContext context,
			BindableFunctionProxyFactory proxyFactory) {
		// here we need to ensure that for cases where composition is defined we only look for supplier method to find Pollable annotation.
		String supplierFunctionName = StringUtils
				.delimitedListToStringArray(proxyFactory.getFunctionDefinition().replaceAll(",", "|").trim(), "|")[0];
		BeanDefinition bd = context.getBeanDefinition(supplierFunctionName);
		if (!(bd instanceof RootBeanDefinition)) {
			return null;
		}

		Method factoryMethod = ((RootBeanDefinition) bd).getResolvedFactoryMethod();
		if (factoryMethod == null) {
			Object source = bd.getSource();
			if (source instanceof MethodMetadata) {
				Class<?> factory = ClassUtils.resolveClassName(((MethodMetadata) source).getDeclaringClassName(), null);
				Class<?>[] params = FunctionContextUtils.getParamTypesFromBeanDefinitionFactory(factory, (RootBeanDefinition) bd);
				factoryMethod = ReflectionUtils.findMethod(factory, ((MethodMetadata) source).getMethodName(), params);
			}
		}
		Assert.notNull(factoryMethod, "Failed to introspect factory method since it was not discovered for function '"
				+ functionProperties.getDefinition() + "'");
		return factoryMethod.getReturnType().isAssignableFrom(Supplier.class)
				? AnnotationUtils.findAnnotation(factoryMethod, PollableBean.class)
						: null;
	}


	@SuppressWarnings("unchecked")
	private <T> Message<T> wrapToMessageIfNecessary(T value) {
		return value instanceof Message
				? (Message<T>) value
						: MessageBuilder.withPayload(value).build();
	}

	private static class FunctionToDestinationBinder implements InitializingBean, ApplicationContextAware {

		protected final Log logger = LogFactory.getLog(getClass());

		private GenericApplicationContext applicationContext;

		private BindableProxyFactory[] bindableProxyFactories;

		private final FunctionCatalog functionCatalog;

		private final StreamFunctionProperties functionProperties;

		private final BindingServiceProperties serviceProperties;

		private final StreamBridge streamBridge;

		FunctionToDestinationBinder(FunctionCatalog functionCatalog, StreamFunctionProperties functionProperties,
				BindingServiceProperties serviceProperties, StreamBridge streamBridge) {
			this.functionCatalog = functionCatalog;
			this.functionProperties = functionProperties;
			this.serviceProperties = serviceProperties;
			this.streamBridge = streamBridge;
		}

		@Override
		public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
			this.applicationContext = (GenericApplicationContext) applicationContext;
		}

		@Override
		public void afterPropertiesSet() throws Exception {
			Map<String, BindableProxyFactory> beansOfType = applicationContext.getBeansOfType(BindableProxyFactory.class);
			this.bindableProxyFactories = beansOfType.values().toArray(new BindableProxyFactory[0]);
			for (BindableProxyFactory bindableProxyFactory : this.bindableProxyFactories) {
				String functionDefinition = bindableProxyFactory instanceof BindableFunctionProxyFactory
						? ((BindableFunctionProxyFactory) bindableProxyFactory).getFunctionDefinition()
								: this.functionProperties.getDefinition();

				boolean shouldNotProcess = false;
				if (!(bindableProxyFactory instanceof BindableFunctionProxyFactory)) {
					Set<String> outputBindingNames = bindableProxyFactory.getOutputs();
					shouldNotProcess = !CollectionUtils.isEmpty(outputBindingNames)
							&& outputBindingNames.iterator().next().equals("applicationMetrics");
				}
				if (StringUtils.hasText(functionDefinition) && !shouldNotProcess) {
					FunctionInvocationWrapper function = functionCatalog.lookup(functionDefinition);
					if (function != null && !function.isSupplier()) {
						this.bindFunctionToDestinations(bindableProxyFactory, functionDefinition);
					}
				}
			}
		}

		@SuppressWarnings({ "rawtypes", "unchecked" })
		private void bindFunctionToDestinations(BindableProxyFactory bindableProxyFactory, String functionDefinition) {
			this.assertBindingIsPossible(bindableProxyFactory);

			Set<String> inputBindingNames = bindableProxyFactory.getInputs();
			Set<String> outputBindingNames = bindableProxyFactory.getOutputs();

			String[] outputContentTypes = outputBindingNames.stream()
					.map(bindingName -> this.serviceProperties.getBindings().get(bindingName).getContentType())
					.toArray(String[]::new);

			FunctionInvocationWrapper function = this.functionCatalog.lookup(functionDefinition, outputContentTypes);
			Type functionType = function.getFunctionType();
			this.assertSupportedSignatures(bindableProxyFactory, function);


			if (this.functionProperties.isComposeFrom()) {
				AbstractSubscribableChannel outputChannel = this.applicationContext.getBean(outputBindingNames.iterator().next(), AbstractSubscribableChannel.class);
				logger.info("Composing at the head of output destination: " + outputChannel.getBeanName());
				String outputChannelName = ((AbstractMessageChannel) outputChannel).getBeanName();
				DirectWithAttributesChannel newOutputChannel = new DirectWithAttributesChannel();
				newOutputChannel.setAttribute("type", "output");
				newOutputChannel.setComponentName("output.extended");
				this.applicationContext.registerBean("output.extended", MessageChannel.class, () -> newOutputChannel);
				bindableProxyFactory.replaceOutputChannel(outputChannelName, "output.extended", newOutputChannel);
				inputBindingNames = Collections.singleton("output");
			}

			if (isReactiveOrMultipleInputOutput(bindableProxyFactory, functionType)) {
				Publisher[] inputPublishers = inputBindingNames.stream().map(inputBindingName -> {
					BindingProperties bindingProperties = this.serviceProperties.getBindings().get(inputBindingName);
					ConsumerProperties consumerProperties = bindingProperties == null ? null : bindingProperties.getConsumer();
					if (consumerProperties != null) {
						function.setSkipInputConversion(consumerProperties.isUseNativeDecoding());
						Assert.isTrue(consumerProperties.getConcurrency() <= 1, "Concurrency > 1 is not supported by reactive "
								+ "consumer, given that project reactor maintains its own concurrency mechanism. Was '..."
								+ inputBindingName + ".consumer.concurrency=" + consumerProperties.getConcurrency() + "'");
					}
					SubscribableChannel inputChannel = this.applicationContext.getBean(inputBindingName, SubscribableChannel.class);
					return IntegrationReactiveUtils.messageChannelToFlux(inputChannel);
				}).toArray(Publisher[]::new);

				Function functionToInvoke = function;
				if (!CollectionUtils.isEmpty(outputBindingNames)) {
					BindingProperties bindingProperties = this.serviceProperties.getBindings().get(outputBindingNames.iterator().next());
					ProducerProperties producerProperties = bindingProperties == null ? null : bindingProperties.getProducer();
					if (producerProperties != null) {
						function.setSkipOutputConversion(producerProperties.isUseNativeEncoding());
					}
					functionToInvoke = new PartitionAwareFunctionWrapper(function, this.applicationContext, producerProperties);
				}

				Object resultPublishers = functionToInvoke.apply(inputPublishers.length == 1 ? inputPublishers[0] : Tuples.fromArray(inputPublishers));

				if (!(resultPublishers instanceof Iterable)) {
					resultPublishers = Collections.singletonList(resultPublishers);
				}
				Iterator<String> outputBindingIter = outputBindingNames.iterator();

				((Iterable) resultPublishers).forEach(publisher -> {
					Flux flux = Flux.from((Publisher) publisher);
					if (!CollectionUtils.isEmpty(outputBindingNames)) {
						MessageChannel outputChannel = this.applicationContext.getBean(outputBindingIter.next(), MessageChannel.class);
						flux = flux.doOnNext(message -> {
							if (message instanceof Message && ((Message<?>) message).getHeaders().get("spring.cloud.stream.sendto.destination") != null) {
								String destinationName = (String) ((Message<?>) message).getHeaders().get("spring.cloud.stream.sendto.destination");
								ProducerProperties producerProperties = this.serviceProperties.getBindings().get(outputBindingNames.iterator().next()).getProducer();
								MessageChannel dynamicChannel = streamBridge.resolveDestination(destinationName, producerProperties);
								if (logger.isInfoEnabled()) {
									logger.info("Output message is sent to '" + destinationName + "' destination");
								}
								if (!(message instanceof Message)) {
									message = MessageBuilder.withPayload(message).build();
								}
								dynamicChannel.send((Message) message);
							}
							else {
								if (!(message instanceof Message)) {
									message = MessageBuilder.withPayload(message).build();
								}
								outputChannel.send((Message) message);
							}
						});
					}
					if (!function.isConsumer()) {
						flux.subscribe();
					}
				});
			}
			else {
				String outputDestinationName = this.determineOutputDestinationName(0, bindableProxyFactory, functionType);
				String inputDestinationName = inputBindingNames.iterator().next();
				Object inputDestination = this.applicationContext.getBean(inputDestinationName);
				if (inputDestination != null && inputDestination instanceof SubscribableChannel) {
					ServiceActivatingHandler handler = createFunctionHandler(function, inputDestinationName, outputDestinationName);
					if (StringUtils.hasText(outputDestinationName)) { // consumer implicit or function<.., mono<void>>
						handler.setOutputChannelName(outputDestinationName);
					}
					((SubscribableChannel) inputDestination).subscribe(handler);
				}
			}
		}

		private ServiceActivatingHandler createFunctionHandler(FunctionInvocationWrapper function,
				String inputChannelName, String outputChannelName) {
			ConsumerProperties consumerProperties = StringUtils.hasText(inputChannelName)
					? this.serviceProperties.getBindingProperties(inputChannelName).getConsumer()
							: null;
			ProducerProperties producerProperties = StringUtils.hasText(outputChannelName)
					? this.serviceProperties.getBindingProperties(outputChannelName).getProducer()
							: null;
			ServiceActivatingHandler handler = new ServiceActivatingHandler(new FunctionWrapper(function, consumerProperties,
					producerProperties, applicationContext)) {
				@Override
				protected void sendOutputs(Object result, Message<?> requestMessage) {
					if (result instanceof Iterable) {
						for (Object resultElement : (Iterable<?>) result) {
							this.doSendMessage(resultElement, requestMessage);
						}
					}
					else if (ObjectUtils.isArray(result) && !(result instanceof byte[])) {
						for (int i = 0; i < ((Object[]) result).length; i++) {
							this.doSendMessage(((Object[]) result)[i], requestMessage);
						}
					}
					else {
						this.doSendMessage(result, requestMessage);
					}
				}

				private void doSendMessage(Object result, Message<?> requestMessage) {
					if (result instanceof Message && ((Message<?>) result).getHeaders().get("spring.cloud.stream.sendto.destination") != null) {
						String destinationName = (String) ((Message<?>) result).getHeaders().get("spring.cloud.stream.sendto.destination");
						SubscribableChannel outputChannel = streamBridge.resolveDestination(destinationName, producerProperties);
						if (logger.isInfoEnabled()) {
							logger.info("Output message is sent to '" + destinationName + "' destination");
						}
						outputChannel.send(((Message<?>) result));
					}
					else {
						super.sendOutputs(result, requestMessage);
					}
				}
			};
			handler.setBeanFactory(this.applicationContext);
			handler.afterPropertiesSet();
			return handler;
		}



		private boolean isReactiveOrMultipleInputOutput(BindableProxyFactory bindableProxyFactory, Type functionType) {
			boolean reactiveInputsOutputs = FunctionTypeUtils.isPublisher(FunctionTypeUtils.getInputType(functionType)) ||
					FunctionTypeUtils.isPublisher(FunctionTypeUtils.getOutputType(functionType));
			return isMultipleInputOutput(bindableProxyFactory) || reactiveInputsOutputs;
		}

		private String determineOutputDestinationName(int index, BindableProxyFactory bindableProxyFactory, Type functionType) {
			List<String> outputNames = new ArrayList<>(bindableProxyFactory.getOutputs());
			if (CollectionUtils.isEmpty(outputNames)) {
				outputNames = Collections.singletonList("output");
			}
			String outputDestinationName = bindableProxyFactory instanceof BindableFunctionProxyFactory
					? ((BindableFunctionProxyFactory) bindableProxyFactory).getOutputName(index)
							: (FunctionTypeUtils.isConsumer(functionType) ? null : outputNames.get(index));
			return outputDestinationName;
		}

		private void assertBindingIsPossible(BindableProxyFactory bindableProxyFactory) {
			if (this.isMultipleInputOutput(bindableProxyFactory)) {
				Assert.isTrue(!functionProperties.isComposeTo() && !functionProperties.isComposeFrom(),
						"Composing to/from existing Sinks and Sources are not supported for functions with multiple arguments.");
			}
		}

		private boolean isMultipleInputOutput(BindableProxyFactory bindableProxyFactory) {
			return bindableProxyFactory instanceof BindableFunctionProxyFactory
					&& ((BindableFunctionProxyFactory) bindableProxyFactory).isMultiple();
		}

		private boolean isArray(Type type) {
			return type instanceof GenericArrayType || type instanceof Class && ((Class<?>) type).isArray();
		}

		private void assertSupportedSignatures(BindableProxyFactory bindableProxyFactory, FunctionInvocationWrapper function) {
			if (this.isMultipleInputOutput(bindableProxyFactory)) {
				Assert.isTrue(!function.isConsumer(),
						"Function '" + functionProperties.getDefinition() + "' is a Consumer which is not supported "
								+ "for multi-in/out reactive streams. Only Functions are supported");
				Assert.isTrue(!function.isSupplier(),
						"Function '" + functionProperties.getDefinition() + "' is a Supplier which is not supported "
								+ "for multi-in/out reactive streams. Only Functions are supported");
				Assert.isTrue(!this.isArray(function.getInputType()) && !this.isArray(function.getOutputType()),
						"Function '" + functionProperties.getDefinition() + "' has the following signature: ["
						+ function.getFunctionType() + "]. Your input and/or outout lacks arity and therefore we "
								+ "can not determine how many input/output destinations are required in the context of "
								+ "function input/output binding.");
			}
		}

	}

	/**
	 *
	 * It's signatures ensures that within the context of s-c-stream Spring Integration does
	 * not attempt any conversion and sends a raw Message.
	 */
	@SuppressWarnings("rawtypes")
	private static class FunctionWrapper implements Function<Message<byte[]>, Object> {
		private final Function function;

		private final ConsumerProperties consumerProperties;

		@SuppressWarnings("unused")
		private final ProducerProperties producerProperties;

		private final Field headersField;

		private final ConfigurableApplicationContext applicationContext;

		private final boolean isRoutingFunction;

		FunctionWrapper(Function function, ConsumerProperties consumerProperties,
				ProducerProperties producerProperties, ConfigurableApplicationContext applicationContext) {

			isRoutingFunction = ((FunctionInvocationWrapper) function).getTarget() instanceof RoutingFunction;
			this.applicationContext = applicationContext;
			this.function = new PartitionAwareFunctionWrapper((FunctionInvocationWrapper) function, this.applicationContext, producerProperties);
			this.consumerProperties = consumerProperties;
			if (this.consumerProperties != null) {
				((FunctionInvocationWrapper) function).setSkipInputConversion(this.consumerProperties.isUseNativeDecoding());
			}
			this.producerProperties = producerProperties;
			if (this.producerProperties != null) {
				((FunctionInvocationWrapper) function).setSkipOutputConversion(this.producerProperties.isUseNativeEncoding());
			}
			this.headersField = ReflectionUtils.findField(MessageHeaders.class, "headers");
			this.headersField.setAccessible(true);
		}

		@SuppressWarnings("unchecked")
		@Override
		public Object apply(Message<byte[]> message) {
			if (message != null && consumerProperties != null) {
				Map<String, Object> headersMap = (Map<String, Object>) ReflectionUtils
						.getField(this.headersField, message.getHeaders());
				headersMap.put(FunctionProperties.SKIP_CONVERSION_HEADER, consumerProperties.isUseNativeDecoding());
			}

			Object result = function.apply(message);
			if (result instanceof Publisher && this.isRoutingFunction) {
				throw new IllegalStateException("Routing to functions that return Publisher "
						+ "is not supported in the context of Spring Cloud Stream.");
			}
			return result;
		}
	}

	private static class PollableSourceRegistrar implements BeanFactoryPostProcessor {
		private final Environment environment;

		PollableSourceRegistrar(Environment environment) {
			this.environment = environment;
		}

		@Override
		public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
			if (StringUtils.hasText(this.environment.getProperty("spring.cloud.stream.pollable-source"))) {
				String[] sourceNames = this.environment.getProperty("spring.cloud.stream.pollable-source").split(";");

				for (String sourceName : sourceNames) {
					RootBeanDefinition functionBindableProxyDefinition = new RootBeanDefinition(BindableFunctionProxyFactory.class);
					functionBindableProxyDefinition.getConstructorArgumentValues().addGenericArgumentValue(sourceName);
					functionBindableProxyDefinition.getConstructorArgumentValues().addGenericArgumentValue(1);
					functionBindableProxyDefinition.getConstructorArgumentValues().addGenericArgumentValue(0);
					functionBindableProxyDefinition.getConstructorArgumentValues().addGenericArgumentValue(new StreamFunctionProperties());
					functionBindableProxyDefinition.getConstructorArgumentValues().addGenericArgumentValue(true);
					((BeanDefinitionRegistry) beanFactory).registerBeanDefinition(sourceName + "_binding", functionBindableProxyDefinition);
				}
			}
		}
	}

	/**
	 * Creates and registers instances of BindableFunctionProxyFactory for each user defined function
	 * thus triggering destination bindings between function arguments and destinations.
	 *
	 * In other words this class is responsible to do the same work as EnableBinding except that it derives the input/output names
	 * from the names of the function (e.g., function-in-0).
	 */
	private static class FunctionBindingRegistrar implements InitializingBean, ApplicationContextAware, EnvironmentAware {

		protected final Log logger = LogFactory.getLog(getClass());

		private final FunctionCatalog functionCatalog;

		private final StreamFunctionProperties streamFunctionProperties;

		private ConfigurableApplicationContext applicationContext;

		private Environment environment;

		private int inputCount;

		private int outputCount;

		FunctionBindingRegistrar(FunctionCatalog functionCatalog, StreamFunctionProperties streamFunctionProperties) {
			this.functionCatalog = functionCatalog;
			this.streamFunctionProperties = streamFunctionProperties;
		}

		@Override
		public void afterPropertiesSet() throws Exception {
			if (ObjectUtils.isEmpty(applicationContext.getBeanNamesForAnnotation(EnableBinding.class))) {
				this.determineFunctionName(functionCatalog, environment);
				BeanDefinitionRegistry registry = (BeanDefinitionRegistry) applicationContext.getBeanFactory();

				if (StringUtils.hasText(streamFunctionProperties.getDefinition())) {
					String[] functionDefinitions = this.filterEligibleFunctionDefinitions();
					for (String functionDefinition : functionDefinitions) {
						RootBeanDefinition functionBindableProxyDefinition = new RootBeanDefinition(BindableFunctionProxyFactory.class);
						FunctionInvocationWrapper function = functionCatalog.lookup(functionDefinition);
						if (function != null) {
							Type functionType = function.getFunctionType();
							if (function.isSupplier()) {
								this.inputCount = 0;
								this.outputCount = this.getOutputCount(functionType, true);
							}
							else if (function.isConsumer()) {
								this.inputCount = FunctionTypeUtils.getInputCount(functionType);
								this.outputCount = 0;
							}
							else {
								this.inputCount = FunctionTypeUtils.getInputCount(functionType);
								this.outputCount = this.getOutputCount(functionType, false);
							}

							functionBindableProxyDefinition.getConstructorArgumentValues().addGenericArgumentValue(functionDefinition);
							functionBindableProxyDefinition.getConstructorArgumentValues().addGenericArgumentValue(this.inputCount);
							functionBindableProxyDefinition.getConstructorArgumentValues().addGenericArgumentValue(this.outputCount);
							functionBindableProxyDefinition.getConstructorArgumentValues().addGenericArgumentValue(this.streamFunctionProperties);
							try {
								String name = functionDefinition + "_binding";
								System.out.println(registry.containsBeanDefinition(name));
								registry.registerBeanDefinition(name, functionBindableProxyDefinition);
							}
							catch (Exception e) {
								e.printStackTrace();
							}

						}
						else {
							logger.warn("The function definition '" + streamFunctionProperties.getDefinition() +
									"' is not valid. The referenced function bean or one of its components does not exist");
						}
					}
				}

				if (StringUtils.hasText(this.environment.getProperty(SOURCE_PROPERY))) {
					String[] sourceNames = this.environment.getProperty(SOURCE_PROPERY).split(";");

					for (String sourceName : sourceNames) {
						if (functionCatalog.lookup(sourceName) == null) {
							RootBeanDefinition functionBindableProxyDefinition = new RootBeanDefinition(BindableFunctionProxyFactory.class);
							functionBindableProxyDefinition.getConstructorArgumentValues().addGenericArgumentValue(sourceName);
							functionBindableProxyDefinition.getConstructorArgumentValues().addGenericArgumentValue(0);
							functionBindableProxyDefinition.getConstructorArgumentValues().addGenericArgumentValue(1);
							functionBindableProxyDefinition.getConstructorArgumentValues().addGenericArgumentValue(this.streamFunctionProperties);
							registry.registerBeanDefinition(sourceName + "_binding", functionBindableProxyDefinition);
						}
					}
				}
			}
			else {
				logger.info("Functional binding is disabled due to the presense of @EnableBinding annotation in your configuration");
			}
		}

		@Override
		public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
			this.applicationContext = (ConfigurableApplicationContext) applicationContext;
		}

		@Override
		public void setEnvironment(Environment environment) {
			this.environment = environment;
		}

		private int getOutputCount(Type functionType, boolean isSupplier) {
			int outputCount = FunctionTypeUtils.getOutputCount(functionType);
			if (!isSupplier && functionType instanceof ParameterizedType) {
				Type outputType = ((ParameterizedType) functionType).getActualTypeArguments()[1];
				if (FunctionTypeUtils.isMono(outputType) && outputType instanceof ParameterizedType
						&& FunctionTypeUtils.getRawType(((ParameterizedType) outputType).getActualTypeArguments()[0]).equals(Void.class)) {
					outputCount = 0;
				}
				else if (FunctionTypeUtils.getRawType(outputType).equals(Void.class)) {
					outputCount = 0;
				}
			}
			return outputCount;
		}

		private boolean determineFunctionName(FunctionCatalog catalog, Environment environment) {
			String definition = streamFunctionProperties.getDefinition();
			if (!StringUtils.hasText(definition)) {
				definition = environment.getProperty("spring.cloud.function.definition");
			}

			if (StringUtils.hasText(definition)) {
				streamFunctionProperties.setDefinition(definition);
			}
			else if (Boolean.parseBoolean(environment.getProperty("spring.cloud.stream.function.routing.enabled", "false"))
					|| environment.containsProperty("spring.cloud.function.routing-expression")) {
				streamFunctionProperties.setDefinition(RoutingFunction.FUNCTION_NAME);
			}
			else {
				streamFunctionProperties.setDefinition(((FunctionInspector) functionCatalog).getName(functionCatalog.lookup("")));
			}
			return StringUtils.hasText(streamFunctionProperties.getDefinition());
		}

		/*
		 * This is to accommodate Kafka streams binder, since it does not rely on binding mechanism provided by s-c-stream core.
		 * So we basically filter out any function name who's type contains KTable or KStream.
		 */
		private String[] filterEligibleFunctionDefinitions() {
			List<String> eligibleFunctionDefinitions = new ArrayList<>();
			String[] functionDefinitions = streamFunctionProperties.getDefinition().split(";");
			for (String functionDefinition : functionDefinitions) {
				String[] functionNames = StringUtils.delimitedListToStringArray(functionDefinition.replaceAll(",", "|").trim(), "|");
				boolean eligibleDefinition = true;
				for (int i = 0; i < functionNames.length && eligibleDefinition; i++) {
					String functionName = functionNames[i];
					if (this.applicationContext.containsBean(functionName)) {
						Object functionBean = this.applicationContext.getBean(functionName);
						Type functionType = FunctionTypeUtils.discoverFunctionType(functionBean, functionName, (GenericApplicationContext) this.applicationContext);
						String functionTypeStringValue = functionType.toString();
						if (functionTypeStringValue.contains("KTable") || functionTypeStringValue.contains("KStream")) {
							eligibleDefinition = false;
						}

					}
					else {
						logger.warn("You have defined function definition that does not exist: " + functionName);
					}
				}
				if (eligibleDefinition) {
					eligibleFunctionDefinitions.add(functionDefinition);
				}
			}
			return eligibleFunctionDefinitions.toArray(new String[0]);
		}

	}
}
