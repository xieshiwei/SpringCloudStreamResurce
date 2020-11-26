/*
 * Copyright 2015-2018 the original author or authors.
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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.stream.messaging.DirectWithAttributesChannel;
import org.springframework.cloud.stream.messaging.Sink;
import org.springframework.cloud.stream.messaging.Source;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.messaging.SubscribableChannel;

/**
 * An implementation of {@link BindingTargetFactory} for creating
 * {@link SubscribableChannel}s.
 *
 * @author Marius Bogoevici
 * @author David Syer
 * @author Ilayaperumal Gopinathan
 * @author Oleg Zhurakousky
 */
public class SubscribableChannelBindingTargetFactory
		extends AbstractBindingTargetFactory<SubscribableChannel> {

	private final MessageChannelConfigurer messageChannelConfigurer;

	@Autowired
	private GenericApplicationContext context;

	public SubscribableChannelBindingTargetFactory(
			MessageChannelConfigurer messageChannelConfigurer) {
		super(SubscribableChannel.class);
		this.messageChannelConfigurer = messageChannelConfigurer;
	}

	@Override
	public SubscribableChannel createInput(String name) {
		DirectWithAttributesChannel subscribableChannel = new DirectWithAttributesChannel();
		subscribableChannel.setComponentName(name);
		subscribableChannel.setAttribute("type", Sink.INPUT);
		this.messageChannelConfigurer.configureInputChannel(subscribableChannel, name);
		if (context != null && !context.containsBean(name)) {
			context.registerBean(name, DirectWithAttributesChannel.class, () -> subscribableChannel);
		}
		return subscribableChannel;
	}

	@Override
	public SubscribableChannel createOutput(String name) {
		DirectWithAttributesChannel subscribableChannel = new DirectWithAttributesChannel();
		subscribableChannel.setComponentName(name);
		subscribableChannel.setAttribute("type", Source.OUTPUT);
		this.messageChannelConfigurer.configureOutputChannel(subscribableChannel, name);
		if (context != null && !context.containsBean(name)) {
			context.registerBean(name, DirectWithAttributesChannel.class, () -> subscribableChannel);
		}
		return subscribableChannel;
	}

}
