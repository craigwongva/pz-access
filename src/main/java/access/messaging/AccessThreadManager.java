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
package access.messaging;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.PostConstruct;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.errors.WakeupException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import messaging.job.JobMessageFactory;
import messaging.job.KafkaClientFactory;
import messaging.job.WorkerCallback;
import model.job.type.AbortJob;
import model.job.type.AccessJob;
import model.request.PiazzaJobRequest;
import util.PiazzaLogger;

/**
 * Manager class for Access Jobs. Handles an incoming Access Job request by creating a GeoServer deployment, or
 * returning a file location. This manages the Thread Pool of Access Workers.
 * 
 * @author Patrick.Doody
 * 
 */
@Component
public class AccessThreadManager {
	private static final String ACCESS_TOPIC_NAME = AccessJob.class.getSimpleName();
	@Autowired
	private PiazzaLogger logger;
	@Autowired
	AccessWorker accessWorker;

	@Value("${vcap.services.pz-kafka.credentials.host}")
	private String KAFKA_ADDRESS;
	private String KAFKA_HOST;
	private String KAFKA_PORT;
	@Value("#{'${kafka.group}' + '-' + '${SPACE}'}")
	private String KAFKA_GROUP;
	@Value("${SPACE}")
	private String SPACE;

	private Producer<String, String> producer;
	private Map<String, Future<?>> runningJobs;
	private final AtomicBoolean closed = new AtomicBoolean(false);

	/**
	 * Manages the Access Jobs Thread Pools
	 */
	public AccessThreadManager() {
	}

	/**
	 * Initializes consumer listeners for the thread pool workers.
	 */
	@PostConstruct
	public void initialize() {
		// Initialize the Kafka Producer
		KAFKA_HOST = KAFKA_ADDRESS.split(":")[0];
		KAFKA_PORT = KAFKA_ADDRESS.split(":")[1];
		producer = KafkaClientFactory.getProducer(KAFKA_HOST, KAFKA_PORT);

		// Initialize the Map of running Threads
		runningJobs = new HashMap<String, Future<?>>();

		// Start polling for Kafka Jobs on the Group Consumer.
		// Occurs on a separate Thread to not block Spring.
		Thread accessJobsThread = new Thread() {
			public void run() {
				pollAccessJobs();
			}
		};
		accessJobsThread.start();

		// Start polling for Kafka Abort Jobs on the unique Consumer.
		Thread pollAbortThread = new Thread() {
			public void run() {
				pollAbortJobs();
			}
		};
		pollAbortThread.start();
	}

	/**
	 * Opens up a Kafka Consumer to poll for all Access Jobs that should be processed by this component.
	 */
	public void pollAccessJobs() {
		try {
			// Callback that will be invoked when a Worker completes. This will
			// remove the Job Id from the running Jobs list.
			WorkerCallback callback = new WorkerCallback() {
				@Override
				public void onComplete(String jobId) {
					runningJobs.remove(jobId);
				}
			};

			// Create the General Group Consumer
			Consumer<String, String> generalConsumer = KafkaClientFactory.getConsumer(KAFKA_HOST, KAFKA_PORT, KAFKA_GROUP);
			generalConsumer.subscribe(Arrays.asList(String.format("%s-%s", ACCESS_TOPIC_NAME, SPACE)));

			// Poll
			while (!closed.get()) {
				ConsumerRecords<String, String> consumerRecords = generalConsumer.poll(1000);
				// Handle new Messages on this topic.
				for (ConsumerRecord<String, String> consumerRecord : consumerRecords) {

					// Create a new worker to process this message and add it to
					// the thread pool.
					Future<?> workerFuture = accessWorker.run(consumerRecord, producer, callback);

					// Keep track of all Running Jobs
					runningJobs.put(consumerRecord.key(), workerFuture);
				}
			}
		} catch (WakeupException exception) {
			logger.log(String.format("Polling Thread forcefully closed: %s", exception.getMessage()), PiazzaLogger.FATAL);
		}
	}

	/**
	 * Begins listening for Abort Jobs. If a Job is owned by this component, then it will be terminated.
	 */
	public void pollAbortJobs() {
		try {
			// Create the Unique Consumer
			Consumer<String, String> uniqueConsumer = KafkaClientFactory.getConsumer(KAFKA_HOST, KAFKA_PORT,
					String.format("%s-%s", KAFKA_GROUP, UUID.randomUUID().toString()));
			uniqueConsumer.subscribe(Arrays.asList(String.format("%s-%s", JobMessageFactory.ABORT_JOB_TOPIC_NAME, SPACE)));

			ObjectMapper mapper = new ObjectMapper();

			// Poll
			while (!closed.get()) {
				ConsumerRecords<String, String> consumerRecords = uniqueConsumer.poll(1000);
				// Handle new Messages on this topic.
				for (ConsumerRecord<String, String> consumerRecord : consumerRecords) {
					// Determine if this Job Id is being processed by this
					// component.
					String jobId = null;
					try {
						PiazzaJobRequest request = mapper.readValue(consumerRecord.value(), PiazzaJobRequest.class);
						jobId = ((AbortJob) request.jobType).getJobId();
					} catch (Exception exception) {
						exception.printStackTrace();
						logger.log(String.format("Error Aborting Job. Could not get the Job ID from the Kafka Message with error:  %s",
								exception.getMessage()), PiazzaLogger.ERROR);
						continue;
					}

					if (runningJobs.containsKey(jobId)) {
						// Cancel the Running Job
						runningJobs.get(jobId).cancel(true);
						// Remove it from the list of Running Jobs
						runningJobs.remove(jobId);
					}
				}
			}
		} catch (WakeupException exception) {
			logger.log(String.format("Polling Thread forcefully closed: %s", exception.getMessage()), PiazzaLogger.FATAL);
		}
	}

	/**
	 * Stops all polling
	 */
	public void stopPolling() {
		this.closed.set(true);
	}

	/**
	 * Returns a list of the Job Ids that are currently being processed by this instance
	 * 
	 * @return The list of Job Ids
	 */
	public List<String> getRunningJobIds() {
		return new ArrayList<String>(runningJobs.keySet());
	}
}
