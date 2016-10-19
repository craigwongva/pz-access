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
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.when;
import model.data.DataResource;
import model.data.deployment.Deployment;
import model.data.location.S3FileStore;
import model.data.type.GeoJsonDataType;
import model.data.type.RasterDataType;
import model.data.type.TextDataType;
import model.data.type.WfsDataType;
import model.job.metadata.SpatialMetadata;

import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import util.PiazzaLogger;
import util.UUIDFactory;
import access.database.Accessor;
import access.deploy.Deployer;
import access.util.AccessUtilities;

/**
 * Tests the Deployer; which handles GeoServer deployments.
 * 
 * @author Patrick.Doody
 *
 */
public class DeployerTests {
	@Mock
	private PiazzaLogger logger;
	@Mock
	private RestTemplate restTemplate;
	@Mock
	private UUIDFactory uuidFactory;
	@Mock
	private Accessor accessor;
	@Mock
	private AccessUtilities accessUtilities;
	@InjectMocks
	private Deployer deployer;

	private DataResource geoJsonData = new DataResource();
	private DataResource textData = new DataResource();
	private DataResource wfsData = new DataResource();
	private DataResource rasterData = new DataResource();

	/**
	 * Test initialization
	 */
	@Before
	public void setup() {
		MockitoAnnotations.initMocks(this);

		// UUID Factory always generates a GUID
		when(uuidFactory.getUUID()).thenReturn("123456");

		// Mock Data
		geoJsonData.setDataId("123456");
		geoJsonData.dataType = new GeoJsonDataType();
		GeoJsonDataType mockDataType = new GeoJsonDataType();
		mockDataType.geoJsonContent = "{\"type\": \"FeatureCollection\",\"features\": [{\"type\": \"Feature\",\"geometry\": {\"type\": \"Point\",\"coordinates\": [102.0,0.5]},\"properties\": {\"prop0\": \"value0\"}},{\"type\": \"Feature\",\"geometry\": {\"type\": \"Point\",\"coordinates\": [106.0,4]},\"properties\": {\"prop0\": \"value0\"}}]}";
		mockDataType.setDatabaseTableName("Test");
		geoJsonData.dataType = mockDataType;
		geoJsonData.spatialMetadata = new SpatialMetadata();
		geoJsonData.spatialMetadata.setEpsgCode(4326);

		textData.setDataId("123456");
		textData.dataType = new TextDataType();
		((TextDataType) textData.dataType).content = "This is a test";

		wfsData.setDataId("123456");
		wfsData.dataType = new WfsDataType();
		wfsData.spatialMetadata = new SpatialMetadata();
		wfsData.spatialMetadata.setEpsgCode(4326);

		rasterData.setDataId("123456");
		RasterDataType rasterType = new RasterDataType();
		S3FileStore location = new S3FileStore("testbucket", "testfile.tif", (long) 5000, "test");
		rasterType.location = location;
		rasterData.dataType = rasterType;
		rasterData.spatialMetadata = new SpatialMetadata();
		rasterData.spatialMetadata.setEpsgCode(4326);
		rasterData.spatialMetadata.setCoordinateReferenceSystem("WGS84");
		rasterData.spatialMetadata.setMinX(0.0);
		rasterData.spatialMetadata.setMinY(1.0);
		rasterData.spatialMetadata.setMaxX(10.0);
		rasterData.spatialMetadata.setMaxY(5.0);
	}

	/**
	 * Tests the creation of deployments
	 */
	@Test
	public void testCreation() throws Exception {
		// Mock
		Mockito.doReturn(new ResponseEntity<String>("OK", HttpStatus.CREATED)).when(restTemplate)
				.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));

		// GeoJSON
		Deployment deployment = deployer.createDeployment(geoJsonData);
		assertTrue(deployment != null);
		assertTrue(deployment.getDataId().equals("123456"));
		assertTrue(deployment.getDeploymentId().equals("123456"));

		// Raster
		Mockito.doReturn(new ResponseEntity<String>("OK", HttpStatus.CREATED)).when(restTemplate)
				.exchange(anyString(), eq(HttpMethod.PUT), any(HttpEntity.class), eq(String.class));
		Mockito.doReturn(new byte[100]).when(accessUtilities).getBytesForDataResource(any(DataResource.class));
		deployment = deployer.createDeployment(rasterData);
		assertTrue(deployment != null);
		assertTrue(deployment.getDataId().equals("123456"));
		assertTrue(deployment.getDeploymentId().equals("123456"));
	}

	/**
	 * Test exception handling in Deployments
	 */
	@Test(expected = Exception.class)
	public void testCreationException() throws Exception {
		// Text - Exception handling
		deployer.createDeployment(textData);
	}

	/**
	 * Tests undeploying resources
	 */
	@Test
	public void testUndeploy() throws Exception {
		// Mock
		Deployment mockDeployment = new Deployment("123456", "123456", "localhost", "8080", "Test", "Test");
		when(accessor.getDeployment(eq("123456"))).thenReturn(mockDeployment);

		// Test. Expect no exceptions.
		deployer.undeploy("123456");
	}

	/**
	 * Tests error handling for undeploying
	 */
	@Test(expected = Exception.class)
	public void testUndeployErrors() throws Exception {
		// Mock
		Deployment mockDeployment = new Deployment("123456", "123456", "localhost", "8080", "Test", "Test");
		when(accessor.getDeployment(eq("123456"))).thenReturn(mockDeployment);
		Mockito.doThrow(new RestClientException("Boom")).when(restTemplate)
				.exchange(anyString(), eq(HttpMethod.DELETE), any(HttpEntity.class), eq(String.class));

		// Test - should throw an exception
		deployer.undeploy("123456");
	}

	/**
	 * Tests the deployment exists check
	 */
	@Test
	public void testDeploymentExist() {
		// Test
		boolean exist = deployer.doesDeploymentExist("123456");
		assertTrue(!exist);
		when(accessor.getDeploymentByDataId(eq("123456"))).thenReturn(new Deployment());
		exist = deployer.doesDeploymentExist("123456");
		assertTrue(exist);
	}
}
