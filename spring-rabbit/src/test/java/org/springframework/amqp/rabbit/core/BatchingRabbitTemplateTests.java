/*
 * Copyright 2014-2019 the original author or authors.
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

package org.springframework.amqp.rabbit.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.Deflater;

import org.apache.commons.logging.Log;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageListener;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.batch.BatchingStrategy;
import org.springframework.amqp.rabbit.batch.SimpleBatchingStrategy;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.junit.BrokerRunning;
import org.springframework.amqp.rabbit.junit.BrokerTestUtils;
import org.springframework.amqp.rabbit.listener.ConditionalRejectingErrorHandler;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.amqp.support.postprocessor.AbstractCompressingPostProcessor;
import org.springframework.amqp.support.postprocessor.DelegatingDecompressingPostProcessor;
import org.springframework.amqp.support.postprocessor.GUnzipPostProcessor;
import org.springframework.amqp.support.postprocessor.GZipPostProcessor;
import org.springframework.amqp.support.postprocessor.UnzipPostProcessor;
import org.springframework.amqp.support.postprocessor.ZipPostProcessor;
import org.springframework.amqp.utils.test.TestUtils;
import org.springframework.beans.DirectFieldAccessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StopWatch;

/**
 * @author Gary Russell
 * @author Artem Bilan
 * @author Mohammad Hewedy
 *
 * @since 1.4.1
 *
 */
public class BatchingRabbitTemplateTests {

	private static final String ROUTE = "test.queue";

	@Rule
	public BrokerRunning brokerIsRunning = BrokerRunning.isRunningWithEmptyQueues(ROUTE);

	private CachingConnectionFactory connectionFactory;

	private ThreadPoolTaskScheduler scheduler;

	@Before
	public void setup() {
		this.connectionFactory = new CachingConnectionFactory();
		this.connectionFactory.setHost("localhost");
		this.connectionFactory.setPort(BrokerTestUtils.getPort());
		scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(1);
		scheduler.initialize();
	}

	@After
	public void tearDown() {
		this.brokerIsRunning.removeTestQueues();
		this.connectionFactory.destroy();
	}

	@Test
	public void testSimpleBatch() throws Exception {
		BatchingStrategy batchingStrategy = new SimpleBatchingStrategy(2, Integer.MAX_VALUE, 30000);
		BatchingRabbitTemplate template = new BatchingRabbitTemplate(batchingStrategy, this.scheduler);
		template.setConnectionFactory(this.connectionFactory);
		MessageProperties props = new MessageProperties();
		Message message = new Message("foo".getBytes(), props);
		template.send("", ROUTE, message);
		message = new Message("bar".getBytes(), props);
		template.send("", ROUTE, message);
		message = receive(template);
		assertThat(new String(message.getBody())).isEqualTo("\u0000\u0000\u0000\u0003foo\u0000\u0000\u0000\u0003bar");
	}

	@Test
	public void testSimpleBatchTimeout() throws Exception {
		BatchingStrategy batchingStrategy = new SimpleBatchingStrategy(2, Integer.MAX_VALUE, 50);
		BatchingRabbitTemplate template = new BatchingRabbitTemplate(batchingStrategy, this.scheduler);
		template.setConnectionFactory(this.connectionFactory);
		MessageProperties props = new MessageProperties();
		Message message = new Message("foo".getBytes(), props);
		template.send("", ROUTE, message);
		message = receive(template);
		assertThat(new String(message.getBody())).isEqualTo("foo");
	}

	@Test
	public void testSimpleBatchTimeoutMultiple() throws Exception {
		BatchingStrategy batchingStrategy = new SimpleBatchingStrategy(2, Integer.MAX_VALUE, 50);
		BatchingRabbitTemplate template = new BatchingRabbitTemplate(batchingStrategy, this.scheduler);
		template.setConnectionFactory(this.connectionFactory);
		MessageProperties props = new MessageProperties();
		Message message = new Message("foo".getBytes(), props);
		template.send("", ROUTE, message);
		template.send("", ROUTE, message);
		message = receive(template);
		assertThat(new String(message.getBody())).isEqualTo("\u0000\u0000\u0000\u0003foo\u0000\u0000\u0000\u0003foo");
	}

	@Test
	public void testSimpleBatchBufferLimit() throws Exception {
		BatchingStrategy batchingStrategy = new SimpleBatchingStrategy(2, 8, 50);
		BatchingRabbitTemplate template = new BatchingRabbitTemplate(batchingStrategy, this.scheduler);
		template.setConnectionFactory(this.connectionFactory);
		MessageProperties props = new MessageProperties();
		Message message = new Message("foo".getBytes(), props);
		template.send("", ROUTE, message);
		message = new Message("bar".getBytes(), props);
		template.send("", ROUTE, message);
		message = receive(template);
		assertThat(new String(message.getBody())).isEqualTo("foo");
		message = receive(template);
		assertThat(new String(message.getBody())).isEqualTo("bar");
	}

	@Test
	public void testSimpleBatchBufferLimitMultiple() throws Exception {
		BatchingStrategy batchingStrategy = new SimpleBatchingStrategy(2, 15, 30000);
		BatchingRabbitTemplate template = new BatchingRabbitTemplate(batchingStrategy, this.scheduler);
		template.setConnectionFactory(this.connectionFactory);
		MessageProperties props = new MessageProperties();
		Message message = new Message("foo".getBytes(), props);
		template.send("", ROUTE, message);
		template.send("", ROUTE, message);
		message = new Message("bar".getBytes(), props);
		template.send("", ROUTE, message);
		template.send("", ROUTE, message);
		message = receive(template);
		assertThat(new String(message.getBody())).isEqualTo("\u0000\u0000\u0000\u0003foo\u0000\u0000\u0000\u0003foo");
		message = receive(template);
		assertThat(new String(message.getBody())).isEqualTo("\u0000\u0000\u0000\u0003bar\u0000\u0000\u0000\u0003bar");
	}

	@Test
	public void testSimpleBatchBiggerThanBufferLimit() throws Exception {
		BatchingStrategy batchingStrategy = new SimpleBatchingStrategy(2, 2, 30000);
		BatchingRabbitTemplate template = new BatchingRabbitTemplate(batchingStrategy, this.scheduler);
		template.setConnectionFactory(this.connectionFactory);
		MessageProperties props = new MessageProperties();
		Message message = new Message("foo".getBytes(), props);
		template.send("", ROUTE, message);
		message = new Message("bar".getBytes(), props);
		template.send("", ROUTE, message);
		message = receive(template);
		assertThat(new String(message.getBody())).isEqualTo("foo");
		message = receive(template);
		assertThat(new String(message.getBody())).isEqualTo("bar");
	}

	@Test
	// existing buffered; new message bigger than bufferLimit; released immediately
	public void testSimpleBatchBiggerThanBufferLimitMultiple() throws Exception {
		BatchingStrategy batchingStrategy = new SimpleBatchingStrategy(2, 6, 30000);
		BatchingRabbitTemplate template = new BatchingRabbitTemplate(batchingStrategy, this.scheduler);
		template.setConnectionFactory(this.connectionFactory);
		MessageProperties props = new MessageProperties();
		Message message = new Message("f".getBytes(), props);
		template.send("", ROUTE, message);
		message = new Message("bar".getBytes(), props);
		template.send("", ROUTE, message);
		message = receive(template);
		assertThat(new String(message.getBody())).isEqualTo("f");
		message = receive(template);
		assertThat(new String(message.getBody())).isEqualTo("bar");
	}

	@Test
	public void testSimpleBatchTwoEqualBufferLimit() throws Exception {
		BatchingStrategy batchingStrategy = new SimpleBatchingStrategy(10, 14, 30000);
		BatchingRabbitTemplate template = new BatchingRabbitTemplate(batchingStrategy, this.scheduler);
		template.setConnectionFactory(this.connectionFactory);
		MessageProperties props = new MessageProperties();
		Message message = new Message("foo".getBytes(), props);
		template.send("", ROUTE, message);
		message = new Message("bar".getBytes(), props);
		template.send("", ROUTE, message);
		message = receive(template);
		assertThat(new String(message.getBody())).isEqualTo("\u0000\u0000\u0000\u0003foo\u0000\u0000\u0000\u0003bar");
	}

	@Test
	public void testDebatchByContainer() throws Exception {
		final List<Message> received = new ArrayList<Message>();
		final CountDownLatch latch = new CountDownLatch(2);
		SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(this.connectionFactory);
		container.setQueueNames(ROUTE);
		List<Boolean> lastInBatch = new ArrayList<>();
		AtomicInteger batchSize = new AtomicInteger();
		container.setMessageListener((MessageListener) message -> {
			received.add(message);
			lastInBatch.add(message.getMessageProperties().isLastInBatch());
			batchSize.set(message.getMessageProperties().getHeader(AmqpHeaders.BATCH_SIZE));
			latch.countDown();
		});
		container.setReceiveTimeout(100);
		container.afterPropertiesSet();
		container.start();
		try {
			BatchingStrategy batchingStrategy = new SimpleBatchingStrategy(2, Integer.MAX_VALUE, 30000);
			BatchingRabbitTemplate template = new BatchingRabbitTemplate(batchingStrategy, this.scheduler);
			template.setConnectionFactory(this.connectionFactory);
			MessageProperties props = new MessageProperties();
			Message message = new Message("foo".getBytes(), props);
			template.send("", ROUTE, message);
			message = new Message("bar".getBytes(), props);
			template.send("", ROUTE, message);
			assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
			assertThat(received).hasSize(2);
			assertThat(new String(received.get(0).getBody())).isEqualTo("foo");
			assertThat(received.get(0).getMessageProperties().getContentLength()).isEqualTo(3);
			assertThat(lastInBatch.get(0)).isFalse();
			assertThat(new String(received.get(1).getBody())).isEqualTo("bar");
			assertThat(received.get(0).getMessageProperties().getContentLength()).isEqualTo(3);
			assertThat(lastInBatch.get(1)).isTrue();
			assertThat(batchSize.get()).isEqualTo(2);
		}
		finally {
			container.stop();
		}
	}

	@Test
	public void testDebatchByContainerPerformance() throws Exception {
		final List<Message> received = new ArrayList<Message>();
		int count = 100000;
		final CountDownLatch latch = new CountDownLatch(count);
		SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(this.connectionFactory);
		container.setQueueNames(ROUTE);
		container.setMessageListener((MessageListener) message -> {
			received.add(message);
			latch.countDown();
		});
		container.setReceiveTimeout(100);
		container.setPrefetchCount(1000);
		container.setBatchSize(1000);
		container.afterPropertiesSet();
		container.start();
		try {
			BatchingStrategy batchingStrategy = new SimpleBatchingStrategy(1000, Integer.MAX_VALUE, 30000);
			BatchingRabbitTemplate template = new BatchingRabbitTemplate(batchingStrategy, this.scheduler);
//			RabbitTemplate template = new RabbitTemplate();
			template.setConnectionFactory(this.connectionFactory);
			MessageProperties props = new MessageProperties();
			props.setDeliveryMode(MessageDeliveryMode.NON_PERSISTENT);
			Message message = new Message(new byte[256], props);
			StopWatch watch = new StopWatch();
			watch.start();
			for (int i = 0; i < count; i++) {
				template.send("", ROUTE, message);
			}
			assertThat(latch.await(60, TimeUnit.SECONDS)).isTrue();
			watch.stop();
			// System .out .println(watch.getTotalTimeMillis());
			assertThat(received).hasSize(count);
		}
		finally {
			container.stop();
		}
	}

	@Test
	public void testDebatchByContainerBadMessageRejected() throws Exception {
		SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(this.connectionFactory);
		container.setQueueNames(ROUTE);
		container.setMessageListener((MessageListener) message -> { });
		container.setReceiveTimeout(100);
		ConditionalRejectingErrorHandler errorHandler = new ConditionalRejectingErrorHandler();
		container.setErrorHandler(errorHandler);
		container.afterPropertiesSet();
		container.start();
		Log logger = spy(TestUtils.getPropertyValue(errorHandler, "logger", Log.class));
		doReturn(true).when(logger).isWarnEnabled();
		doNothing().when(logger).warn(anyString(), any(Throwable.class));
		new DirectFieldAccessor(errorHandler).setPropertyValue("logger", logger);
		try {
			RabbitTemplate template = new RabbitTemplate();
			template.setConnectionFactory(this.connectionFactory);
			MessageProperties props = new MessageProperties();
			props.getHeaders().put(MessageProperties.SPRING_BATCH_FORMAT, MessageProperties.BATCH_FORMAT_LENGTH_HEADER4);
			Message message = new Message("\u0000\u0000\u0000\u0004foo".getBytes(), props);
			template.send("", ROUTE, message);
			Thread.sleep(1000);
			ArgumentCaptor<Object> arg1 = ArgumentCaptor.forClass(Object.class);
			ArgumentCaptor<Throwable> arg2 = ArgumentCaptor.forClass(Throwable.class);
			verify(logger).warn(arg1.capture(), arg2.capture());
			assertThat(arg2.getValue().getMessage()).contains("Bad batched message received");
		}
		finally {
			container.stop();
		}
	}

	@Test
	public void testSimpleBatchGZipped() throws Exception {
		BatchingStrategy batchingStrategy = new SimpleBatchingStrategy(2, Integer.MAX_VALUE, 30000);
		BatchingRabbitTemplate template = new BatchingRabbitTemplate(batchingStrategy, this.scheduler);
		template.setConnectionFactory(this.connectionFactory);
		GZipPostProcessor gZipPostProcessor = new GZipPostProcessor();
		assertThat(getStreamLevel(gZipPostProcessor)).isEqualTo(Deflater.BEST_SPEED);
		template.setBeforePublishPostProcessors(gZipPostProcessor);
		MessageProperties props = new MessageProperties();
		Message message = new Message("foo".getBytes(), props);
		template.send("", ROUTE, message);
		message = new Message("bar".getBytes(), props);
		template.send("", ROUTE, message);
		message = receive(template);
		assertThat(message.getMessageProperties().getContentEncoding()).isEqualTo("gzip");
		GUnzipPostProcessor unzipper = new GUnzipPostProcessor();
		message = unzipper.postProcessMessage(message);
		assertThat(new String(message.getBody())).isEqualTo("\u0000\u0000\u0000\u0003foo\u0000\u0000\u0000\u0003bar");
	}

	@Test
	public void testSimpleBatchGZippedUsingAdd() throws Exception {
		BatchingStrategy batchingStrategy = new SimpleBatchingStrategy(2, Integer.MAX_VALUE, 30000);
		BatchingRabbitTemplate template = new BatchingRabbitTemplate(batchingStrategy, this.scheduler);
		template.setConnectionFactory(this.connectionFactory);
		GZipPostProcessor gZipPostProcessor = new GZipPostProcessor();
		assertThat(getStreamLevel(gZipPostProcessor)).isEqualTo(Deflater.BEST_SPEED);
		template.addBeforePublishPostProcessors(gZipPostProcessor);
		MessageProperties props = new MessageProperties();
		Message message = new Message("foo".getBytes(), props);
		template.send("", ROUTE, message);
		message = new Message("bar".getBytes(), props);
		template.send("", ROUTE, message);
		message = receive(template);
		assertThat(message.getMessageProperties().getContentEncoding()).isEqualTo("gzip");
		GUnzipPostProcessor unzipper = new GUnzipPostProcessor();
		message = unzipper.postProcessMessage(message);
		assertThat(new String(message.getBody())).isEqualTo("\u0000\u0000\u0000\u0003foo\u0000\u0000\u0000\u0003bar");
	}

	@Test
	public void testSimpleBatchGZippedUsingAddAndRemove() throws Exception {
		BatchingStrategy batchingStrategy = new SimpleBatchingStrategy(2, Integer.MAX_VALUE, 30000);
		BatchingRabbitTemplate template = new BatchingRabbitTemplate(batchingStrategy, this.scheduler);
		template.setConnectionFactory(this.connectionFactory);
		GZipPostProcessor gZipPostProcessor = new GZipPostProcessor();
		assertThat(getStreamLevel(gZipPostProcessor)).isEqualTo(Deflater.BEST_SPEED);
		template.addBeforePublishPostProcessors(gZipPostProcessor);
		HeaderPostProcessor headerPostProcessor = new HeaderPostProcessor();
		template.addBeforePublishPostProcessors(headerPostProcessor);
		template.removeBeforePublishPostProcessor(headerPostProcessor);
		MessageProperties props = new MessageProperties();
		Message message = new Message("foo".getBytes(), props);
		template.send("", ROUTE, message);
		message = new Message("bar".getBytes(), props);
		template.send("", ROUTE, message);
		message = receive(template);
		assertThat(message.getMessageProperties().getContentEncoding()).isEqualTo("gzip");
		GUnzipPostProcessor unzipper = new GUnzipPostProcessor();
		message = unzipper.postProcessMessage(message);
		assertThat(new String(message.getBody())).isEqualTo("\u0000\u0000\u0000\u0003foo\u0000\u0000\u0000\u0003bar");
		assertThat(message.getMessageProperties().getHeaders().get("someHeader")).isNull();
	}

	@Test
	public void testSimpleBatchGZippedConfiguredUnzipper() throws Exception {
		BatchingStrategy batchingStrategy = new SimpleBatchingStrategy(2, Integer.MAX_VALUE, 30000);
		BatchingRabbitTemplate template = new BatchingRabbitTemplate(batchingStrategy, this.scheduler);
		template.setConnectionFactory(this.connectionFactory);
		GZipPostProcessor gZipPostProcessor = new GZipPostProcessor();
		gZipPostProcessor.setLevel(Deflater.BEST_COMPRESSION);
		assertThat(getStreamLevel(gZipPostProcessor)).isEqualTo(Deflater.BEST_COMPRESSION);
		template.setBeforePublishPostProcessors(gZipPostProcessor);
		template.setAfterReceivePostProcessors(new GUnzipPostProcessor());
		MessageProperties props = new MessageProperties();
		Message message = new Message("foo".getBytes(), props);
		template.send("", ROUTE, message);
		message = new Message("bar".getBytes(), props);
		template.send("", ROUTE, message);
		message = receive(template);
		assertThat(message.getMessageProperties().getContentEncoding()).isNull();
		assertThat(new String(message.getBody())).isEqualTo("\u0000\u0000\u0000\u0003foo\u0000\u0000\u0000\u0003bar");
	}

	@Test
	public void testSimpleBatchGZippedConfiguredUnzipperUsingAdd() throws Exception {
		BatchingStrategy batchingStrategy = new SimpleBatchingStrategy(2, Integer.MAX_VALUE, 30000);
		BatchingRabbitTemplate template = new BatchingRabbitTemplate(batchingStrategy, this.scheduler);
		template.setConnectionFactory(this.connectionFactory);
		GZipPostProcessor gZipPostProcessor = new GZipPostProcessor();
		gZipPostProcessor.setLevel(Deflater.BEST_COMPRESSION);
		assertThat(getStreamLevel(gZipPostProcessor)).isEqualTo(Deflater.BEST_COMPRESSION);
		template.addBeforePublishPostProcessors(gZipPostProcessor);
		template.addAfterReceivePostProcessors(new GUnzipPostProcessor());
		MessageProperties props = new MessageProperties();
		Message message = new Message("foo".getBytes(), props);
		template.send("", ROUTE, message);
		message = new Message("bar".getBytes(), props);
		template.send("", ROUTE, message);
		message = receive(template);
		assertThat(message.getMessageProperties().getContentEncoding()).isNull();
		assertThat(new String(message.getBody())).isEqualTo("\u0000\u0000\u0000\u0003foo\u0000\u0000\u0000\u0003bar");
	}

	@Test
	public void testSimpleBatchGZippedWithEncoding() throws Exception {
		BatchingStrategy batchingStrategy = new SimpleBatchingStrategy(2, Integer.MAX_VALUE, 30000);
		BatchingRabbitTemplate template = new BatchingRabbitTemplate(batchingStrategy, this.scheduler);
		template.setConnectionFactory(this.connectionFactory);
		template.setBeforePublishPostProcessors(new GZipPostProcessor());
		MessageProperties props = new MessageProperties();
		props.setContentEncoding("foo");
		Message message = new Message("foo".getBytes(), props);
		template.send("", ROUTE, message);
		message = new Message("bar".getBytes(), props);
		template.send("", ROUTE, message);
		message = receive(template);
		assertThat(message.getMessageProperties().getContentEncoding()).isEqualTo("gzip:foo");
		GUnzipPostProcessor unzipper = new GUnzipPostProcessor();
		message = unzipper.postProcessMessage(message);
		assertThat(new String(message.getBody())).isEqualTo("\u0000\u0000\u0000\u0003foo\u0000\u0000\u0000\u0003bar");
	}

	@Test
	public void testSimpleBatchGZippedWithEncodingInflated() throws Exception {
		BatchingStrategy batchingStrategy = new SimpleBatchingStrategy(2, Integer.MAX_VALUE, 30000);
		BatchingRabbitTemplate template = new BatchingRabbitTemplate(batchingStrategy, this.scheduler);
		template.setConnectionFactory(this.connectionFactory);
		template.setBeforePublishPostProcessors(new GZipPostProcessor());
		template.setAfterReceivePostProcessors(new DelegatingDecompressingPostProcessor());
		MessageProperties props = new MessageProperties();
		props.setContentEncoding("foo");
		Message message = new Message("foo".getBytes(), props);
		template.send("", ROUTE, message);
		message = new Message("bar".getBytes(), props);
		template.send("", ROUTE, message);
		Thread.sleep(100);
		byte[] out = (byte[]) template.receiveAndConvert(ROUTE);
		assertThat(out).isNotNull();
		assertThat(new String(out)).isEqualTo("\u0000\u0000\u0000\u0003foo\u0000\u0000\u0000\u0003bar");
	}

	@Test
	public void testSimpleBatchZippedBestCompression() throws Exception {
		BatchingStrategy batchingStrategy = new SimpleBatchingStrategy(2, Integer.MAX_VALUE, 30000);
		BatchingRabbitTemplate template = new BatchingRabbitTemplate(batchingStrategy, this.scheduler);
		template.setConnectionFactory(this.connectionFactory);
		ZipPostProcessor zipPostProcessor = new ZipPostProcessor();
		zipPostProcessor.setLevel(Deflater.BEST_COMPRESSION);
		assertThat(getStreamLevel(zipPostProcessor)).isEqualTo(Deflater.BEST_COMPRESSION);
		template.setBeforePublishPostProcessors(zipPostProcessor);
		MessageProperties props = new MessageProperties();
		Message message = new Message("foo".getBytes(), props);
		template.send("", ROUTE, message);
		message = new Message("bar".getBytes(), props);
		template.send("", ROUTE, message);
		message = receive(template);
		assertThat(message.getMessageProperties().getContentEncoding()).isEqualTo("zip");
		UnzipPostProcessor unzipper = new UnzipPostProcessor();
		message = unzipper.postProcessMessage(message);
		assertThat(new String(message.getBody())).isEqualTo("\u0000\u0000\u0000\u0003foo\u0000\u0000\u0000\u0003bar");
	}

	@Test
	public void testSimpleBatchZippedWithEncoding() throws Exception {
		BatchingStrategy batchingStrategy = new SimpleBatchingStrategy(2, Integer.MAX_VALUE, 30000);
		BatchingRabbitTemplate template = new BatchingRabbitTemplate(batchingStrategy, this.scheduler);
		template.setConnectionFactory(this.connectionFactory);
		ZipPostProcessor zipPostProcessor = new ZipPostProcessor();
		assertThat(getStreamLevel(zipPostProcessor)).isEqualTo(Deflater.BEST_SPEED);
		template.setBeforePublishPostProcessors(zipPostProcessor);
		MessageProperties props = new MessageProperties();
		props.setContentEncoding("foo");
		Message message = new Message("foo".getBytes(), props);
		template.send("", ROUTE, message);
		message = new Message("bar".getBytes(), props);
		template.send("", ROUTE, message);
		message = receive(template);
		assertThat(message.getMessageProperties().getContentEncoding()).isEqualTo("zip:foo");
		UnzipPostProcessor unzipper = new UnzipPostProcessor();
		message = unzipper.postProcessMessage(message);
		assertThat(new String(message.getBody())).isEqualTo("\u0000\u0000\u0000\u0003foo\u0000\u0000\u0000\u0003bar");
	}

	private Message receive(BatchingRabbitTemplate template) throws InterruptedException {
		Message message = template.receive(ROUTE);
		int n = 0;
		while (n++ < 200 && message == null) {
			Thread.sleep(50);
			message = template.receive(ROUTE);
		}
		assertThat(message).isNotNull();
		return message;
	}

	@Test
	public void testCompressionWithContainer() throws Exception {
		final List<Message> received = new ArrayList<Message>();
		final CountDownLatch latch = new CountDownLatch(2);
		SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(this.connectionFactory);
		container.setQueueNames(ROUTE);
		container.setMessageListener((MessageListener) message -> {
			received.add(message);
			latch.countDown();
		});
		container.setReceiveTimeout(100);
		container.setAfterReceivePostProcessors(new DelegatingDecompressingPostProcessor());
		container.afterPropertiesSet();
		container.start();
		try {
			BatchingStrategy batchingStrategy = new SimpleBatchingStrategy(2, Integer.MAX_VALUE, 30000);
			BatchingRabbitTemplate template = new BatchingRabbitTemplate(batchingStrategy, this.scheduler);
			template.setConnectionFactory(this.connectionFactory);
			template.setBeforePublishPostProcessors(new GZipPostProcessor());
			MessageProperties props = new MessageProperties();
			Message message = new Message("foo".getBytes(), props);
			template.send("", ROUTE, message);
			message = new Message("bar".getBytes(), props);
			template.send("", ROUTE, message);
			assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
			assertThat(received).hasSize(2);
			assertThat(new String(received.get(0).getBody())).isEqualTo("foo");
			assertThat(received.get(0).getMessageProperties().getContentLength()).isEqualTo(3);
			assertThat(new String(received.get(1).getBody())).isEqualTo("bar");
			assertThat(received.get(0).getMessageProperties().getContentLength()).isEqualTo(3);
		}
		finally {
			container.stop();
		}
	}

	private int getStreamLevel(Object stream) throws Exception {
		final AtomicReference<Method> m = new AtomicReference<Method>();
		ReflectionUtils.doWithMethods(AbstractCompressingPostProcessor.class, method -> {
			method.setAccessible(true);
			m.set(method);
		}, method -> method.getName().equals("getCompressorStream"));
		Object zipStream = m.get().invoke(stream, mock(OutputStream.class));
		return TestUtils.getPropertyValue(zipStream, "def.level", Integer.class);
	}

	private static class HeaderPostProcessor implements MessagePostProcessor {
		@Override
		public Message postProcessMessage(Message message) throws AmqpException {
			message.getMessageProperties().getHeaders().put("someHeader", "someValue");
			return message;
		}
	}
}
