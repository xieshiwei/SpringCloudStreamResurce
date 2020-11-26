/*
 * Copyright 2019-2019 the original author or authors.
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

package org.springframework.cloud.stream.binding;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.stream.binder.Binding;
import org.springframework.cloud.stream.internal.InternalPropertyNames;
import org.springframework.util.StringUtils;

/**
 * Base class for bindable proxy factories. This class is mainly refactored from the
 * {@link BindableProxyFactory} so that other downstream binders who want to bind their own
 * targets can make use of it.
 *
 * Original authors in {@link BindableProxyFactory}
 * @author Soby Chacko
 * @since 3.0.0
 */
public class AbstractBindableProxyFactory implements Bindable {

	private static Log log = LogFactory.getLog(AbstractBindableProxyFactory.class);

	@Value("${" + InternalPropertyNames.NAMESPACE_PROPERTY_NAME + ":}")
	private String namespace;

	@Autowired
	protected Map<String, BindingTargetFactory> bindingTargetFactories;

	protected Map<String, BoundTargetHolder> inputHolders = new LinkedHashMap<>();

	protected Map<String, BoundTargetHolder> outputHolders = new LinkedHashMap<>();

	protected Class<?> type;

	public AbstractBindableProxyFactory(Class<?> type) {
		this.type = type;
	}

	protected BindingTargetFactory getBindingTargetFactory(Class<?> bindingTargetType) {
		List<String> candidateBindingTargetFactories = new ArrayList<>();
		for (Map.Entry<String, BindingTargetFactory> bindingTargetFactoryEntry : this.bindingTargetFactories
			.entrySet()) {
			if (bindingTargetFactoryEntry.getValue().canCreate(bindingTargetType)) {
				candidateBindingTargetFactories.add(bindingTargetFactoryEntry.getKey());
			}
		}
		if (candidateBindingTargetFactories.size() == 1) {
			return this.bindingTargetFactories
				.get(candidateBindingTargetFactories.get(0));
		}
		else {
			if (candidateBindingTargetFactories.size() == 0) {
				throw new IllegalStateException(
					"No factory found for binding target type: "
						+ bindingTargetType.getName()
						+ " among registered factories: "
						+ StringUtils.collectionToCommaDelimitedString(
						this.bindingTargetFactories.keySet()));
			}
			else {
				throw new IllegalStateException(
					"Multiple factories found for binding target type: "
						+ bindingTargetType.getName() + ": "
						+ StringUtils.collectionToCommaDelimitedString(
						candidateBindingTargetFactories));
			}
		}
	}

	@Override
	public Collection<Binding<Object>> createAndBindInputs(
		BindingService bindingService) {
		List<Binding<Object>> bindings = new ArrayList<>();
		if (log.isDebugEnabled()) {
			log.debug(
				String.format("Binding inputs for %s:%s", this.namespace, this.type));
		}
		for (Map.Entry<String, BoundTargetHolder> boundTargetHolderEntry : this.inputHolders
			.entrySet()) {
			String inputTargetName = boundTargetHolderEntry.getKey();
			BoundTargetHolder boundTargetHolder = boundTargetHolderEntry.getValue();
			if (boundTargetHolder.isBindable()) {
				if (log.isDebugEnabled()) {
					log.debug(String.format("Binding %s:%s:%s", this.namespace, this.type,
						inputTargetName));
				}
				bindings.addAll(bindingService.bindConsumer(
					boundTargetHolder.getBoundTarget(), inputTargetName));
			}
		}
		return bindings;
	}

	@Override
	public Collection<Binding<Object>> createAndBindOutputs(
		BindingService bindingService) {
		List<Binding<Object>> bindings = new ArrayList<>();
		if (log.isDebugEnabled()) {
			log.debug(String.format("Binding outputs for %s:%s", this.namespace,
				this.type));
		}
		for (Map.Entry<String, BoundTargetHolder> boundTargetHolderEntry : this.outputHolders
			.entrySet()) {
			BoundTargetHolder boundTargetHolder = boundTargetHolderEntry.getValue();
			String outputTargetName = boundTargetHolderEntry.getKey();
			if (boundTargetHolderEntry.getValue().isBindable()) {
				if (log.isDebugEnabled()) {
					log.debug(String.format("Binding %s:%s:%s", this.namespace, this.type,
						outputTargetName));
				}
				bindings.add(bindingService.bindProducer(
					boundTargetHolder.getBoundTarget(), outputTargetName));
			}
		}
		return bindings;
	}

	@Override
	public void unbindInputs(BindingService bindingService) {
		if (log.isDebugEnabled()) {
			log.debug(String.format("Unbinding inputs for %s:%s", this.namespace,
				this.type));
		}
		for (Map.Entry<String, BoundTargetHolder> boundTargetHolderEntry : this.inputHolders
			.entrySet()) {
			if (boundTargetHolderEntry.getValue().isBindable()) {
				if (log.isDebugEnabled()) {
					log.debug(String.format("Unbinding %s:%s:%s", this.namespace,
						this.type, boundTargetHolderEntry.getKey()));
				}
				bindingService.unbindConsumers(boundTargetHolderEntry.getKey());
			}
		}
	}

	@Override
	public void unbindOutputs(BindingService bindingService) {
		if (log.isDebugEnabled()) {
			log.debug(String.format("Unbinding outputs for %s:%s", this.namespace,
				this.type));
		}
		for (Map.Entry<String, BoundTargetHolder> boundTargetHolderEntry : this.outputHolders
			.entrySet()) {
			if (boundTargetHolderEntry.getValue().isBindable()) {
				if (log.isDebugEnabled()) {
					log.debug(String.format("Binding %s:%s:%s", this.namespace, this.type,
						boundTargetHolderEntry.getKey()));
				}
				bindingService.unbindProducers(boundTargetHolderEntry.getKey());
			}
		}
	}

	@Override
	public Set<String> getInputs() {
		return this.inputHolders.keySet();
	}

	@Override
	public Set<String> getOutputs() {
		return this.outputHolders.keySet();
	}
}
