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
import static org.mockito.Matchers.*;
import static org.mockito.Mockito.when;
import model.data.deployment.Deployment;
import model.data.deployment.Lease;

import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import util.PiazzaLogger;
import util.UUIDFactory;
import access.database.Accessor;
import access.deploy.Deployer;
import access.deploy.Leaser;

/**
 * Tests the leaser class
 * 
 * @author Patrick.Doody
 *
 */
public class LeaserTests {
	@Mock
	private Deployer deployer;
	@Mock
	private PiazzaLogger logger;
	@Mock
	private UUIDFactory uuidFactory;
	@Mock
	private Accessor accessor;
	@InjectMocks
	private Leaser leaser;

	/**
	 * Test initialization
	 */
	@Before
	public void setup() {
		MockitoAnnotations.initMocks(this);
	}

	/**
	 * Tests renewing a deployment lease
	 */
	@Test
	public void testRenewLease() {
		// Mock
		Lease mockLease = new Lease("123456", "123456", new DateTime().minusDays(1).toString());
		Deployment mockDeployment = new Deployment("123456", "123456", "localhost", "8080", "Test", "localhost:8080");

		// Test when lease exists
		when(accessor.getDeploymentLease(any(Deployment.class))).thenReturn(mockLease);
		Lease lease = leaser.renewDeploymentLease(mockDeployment, 21);
		assertTrue(lease != null);
		assertTrue(lease.getLeaseId().equals("123456"));

		// Test when lease needs to be created
		when(accessor.getDeploymentLease(any(Deployment.class))).thenReturn(null);
		when(uuidFactory.getUUID()).thenReturn("654321");
		lease = leaser.renewDeploymentLease(mockDeployment, 21);
		assertTrue(lease != null);
		assertTrue(lease.getLeaseId().equals("654321"));
		assertTrue(new DateTime(lease.getExpiresOn()).isAfter(new DateTime().plusDays(20)));
	}
}
