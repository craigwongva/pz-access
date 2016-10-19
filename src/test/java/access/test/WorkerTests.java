/**
 * Copyright 2016, RadiantBlue Technologies, Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **/
package access.test;

import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

import messaging.job.WorkerCallback;
import model.job.Job;
import model.job.type.AccessJob;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import util.PiazzaLogger;
import access.database.Accessor;
import access.deploy.Deployer;
import access.deploy.Leaser;
import access.messaging.AccessWorker;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Tests the Access Worker, which processes Kafka messages
 * 
 * @author Patrick.Doody
 *
 */
public class WorkerTests {
	@Mock
	private Deployer deployer;
	@Mock
	private Accessor mongoAccessor;
	@Mock
	private Leaser leaser;
	@Mock
	private PiazzaLogger logger;
	@Mock
	private Producer<String, String> producer;
	@InjectMocks
	private AccessWorker worker;

	/**
	 * Test initialization
	 */
	@Before
	public void setup() {
		MockitoAnnotations.initMocks(this);

		// Mock the Kafka response that Producers will send. This will always
		// return a Future that completes immediately and simply returns true.
		when(producer.send(isA(ProducerRecord.class))).thenAnswer(new Answer<Future<Boolean>>() {
			@Override
			public Future<Boolean> answer(InvocationOnMock invocation) throws Throwable {
				Future<Boolean> future = mock(FutureTask.class);
				when(future.isDone()).thenReturn(true);
				when(future.get()).thenReturn(true);
				return future;
			}
		});
	}

	/**
	 * Tests handling a Kafka message. Verify no exceptions.
	 */
	@Test
	public void testWorker() throws Exception {
		// Mock
		Job mockJob = new Job();
		mockJob.jobId = "123456";
		mockJob.createdOn = new DateTime();
		mockJob.createdBy = "Test User";
		AccessJob accessJob = new AccessJob("123456");
		accessJob.deploymentType = AccessJob.ACCESS_TYPE_GEOSERVER;
		accessJob.dataId = "123456";
		mockJob.jobType = accessJob;
		ConsumerRecord<String, String> mockRecord = new ConsumerRecord<String, String>("Access", 0, 0, "123456",
				new ObjectMapper().writeValueAsString(mockJob));
		WorkerCallback callback = new WorkerCallback() {
			@Override
			public void onComplete(String jobId) {
				assertTrue(jobId.equals("123456"));
			}
		};

		// Test when refreshing an expired lease
		when(deployer.doesDeploymentExist(eq("123456"))).thenReturn(true);
		worker.run(mockRecord, producer, callback);

		// Test when a current lease doesn't exist - new is created
		when(deployer.doesDeploymentExist(eq("123456"))).thenReturn(false);
		worker.run(mockRecord, producer, callback);

		// Test inner exceptions during deployment
		accessJob.deploymentType = "Mock";
		mockRecord = new ConsumerRecord<String, String>("Access", 0, 0, "123456",
				new ObjectMapper().writeValueAsString(mockJob));
		worker.run(mockRecord, producer, callback);
	}
}
