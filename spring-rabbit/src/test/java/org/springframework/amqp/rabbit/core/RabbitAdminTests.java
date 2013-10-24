/*
 * Copyright 2002-2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.springframework.amqp.rabbit.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import java.util.Properties;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.SingleConnectionFactory;
import org.springframework.amqp.rabbit.test.BrokerRunning;
import org.springframework.context.support.GenericApplicationContext;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DefaultConsumer;

public class RabbitAdminTests {

	@Rule
	public ExpectedException exception = ExpectedException.none();

	@Rule
	public BrokerRunning brokerIsRunning = BrokerRunning.isRunning();

	@Test
	public void testSettingOfNullRabbitTemplate() {
		ConnectionFactory connectionFactory = null;
		try {
			new RabbitAdmin(connectionFactory);
			fail("should have thrown IllegalStateException when RabbitTemplate is not set.");
		} catch (IllegalArgumentException e) {

		}
	}

	@Test
	public void testNoFailOnStartupWithMissingBroker() throws Exception {
		SingleConnectionFactory connectionFactory = new SingleConnectionFactory("foo");
		connectionFactory.setPort(434343);
		GenericApplicationContext applicationContext = new GenericApplicationContext();
		applicationContext.getBeanFactory().registerSingleton("foo", new Queue("queue"));
		RabbitAdmin rabbitAdmin = new RabbitAdmin(connectionFactory);
		rabbitAdmin.setApplicationContext(applicationContext);
		rabbitAdmin.setAutoStartup(true);
		rabbitAdmin.afterPropertiesSet();
		connectionFactory.destroy();
	}

	@Test
	public void testFailOnFirstUseWithMissingBroker() throws Exception {
		SingleConnectionFactory connectionFactory = new SingleConnectionFactory("foo");
		connectionFactory.setPort(434343);
		GenericApplicationContext applicationContext = new GenericApplicationContext();
		applicationContext.getBeanFactory().registerSingleton("foo", new Queue("queue"));
		RabbitAdmin rabbitAdmin = new RabbitAdmin(connectionFactory);
		rabbitAdmin.setApplicationContext(applicationContext);
		rabbitAdmin.setAutoStartup(true);
		rabbitAdmin.afterPropertiesSet();
		exception.expect(IllegalArgumentException.class);
		rabbitAdmin.declareQueue();
		connectionFactory.destroy();
	}

	@Test
	public void testProperties() throws Exception {
		SingleConnectionFactory connectionFactory = new SingleConnectionFactory();
		RabbitAdmin rabbitAdmin = new RabbitAdmin(connectionFactory);
		String queueName = "test.properties." + System.currentTimeMillis();
		try {
			rabbitAdmin.declareQueue(new Queue(queueName));
			new RabbitTemplate(connectionFactory).convertAndSend(queueName, "foo");
			Properties props = rabbitAdmin.getQueueProperties(queueName);
			assertNotNull(props);
			assertNotNull(props.get(RabbitAdmin.QUEUE_MESSAGE_COUNT));
			assertEquals(1, props.get(RabbitAdmin.QUEUE_MESSAGE_COUNT));
			Channel channel = connectionFactory.createConnection().createChannel(false);
			DefaultConsumer consumer = new DefaultConsumer(channel);
			channel.basicConsume(queueName, true, consumer);
			props = rabbitAdmin.getQueueProperties(queueName);
			assertNotNull(props);
			assertNotNull(props.get(RabbitAdmin.QUEUE_MESSAGE_COUNT));
			assertEquals(0, props.get(RabbitAdmin.QUEUE_MESSAGE_COUNT));
			assertNotNull(props.get(RabbitAdmin.QUEUE_CONSUMER_COUNT));
			assertEquals(1, props.get(RabbitAdmin.QUEUE_CONSUMER_COUNT));
			channel.close();
		}
		finally {
			rabbitAdmin.deleteQueue(queueName);
			connectionFactory.destroy();
		}
	}

	@Test
	public void testExistsExchange() throws Exception {
		SingleConnectionFactory cf = new SingleConnectionFactory();
		RabbitAdmin admin = new RabbitAdmin(cf);
		String exchangeName = "test.exchange." + System.currentTimeMillis();
		try {
			admin.declareExchange(new DirectExchange(exchangeName));
			assertEquals(true, admin.existsExchange(exchangeName));
			assertEquals(false, admin.existsExchange("not.exists.exchange"));
		} finally {
			admin.deleteExchange(exchangeName);
			cf.destroy();
		}
	}

}
