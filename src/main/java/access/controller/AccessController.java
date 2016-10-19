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
package access.controller;

import java.io.InputStream;
import java.io.StringWriter;
import java.security.Principal;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.io.FilenameUtils;
import org.geotools.data.DataStore;
import org.geotools.data.Query;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.data.simple.SimpleFeatureSource;
import org.geotools.geojson.feature.FeatureJSON;
import org.opengis.feature.simple.SimpleFeature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;

import com.amazonaws.util.StringUtils;

import access.database.Accessor;
import access.deploy.Deployer;
import access.deploy.GroupDeployer;
import access.deploy.Leaser;
import access.messaging.AccessThreadManager;
import model.data.DataResource;
import model.data.FileRepresentation;
import model.data.deployment.Deployment;
import model.data.deployment.DeploymentGroup;
import model.data.deployment.Lease;
import model.data.location.FileAccessFactory;
import model.data.type.PostGISDataType;
import model.data.type.TextDataType;
import model.response.DataResourceResponse;
import model.response.DeploymentGroupResponse;
import model.response.DeploymentResponse;
import model.response.ErrorResponse;
import model.response.PiazzaResponse;
import model.response.SuccessResponse;
import util.PiazzaLogger;

/**
 * Allows for synchronous fetching of Resource Data from the Mongo Resource collection.
 * 
 * The collection is bound to the DataResource model.
 * 
 * This controller is similar to the functionality of the JobManager REST Controller, in that this component primarily
 * listens for messages via Kafka, however, for instances where the user needs a direct read out of the database - this
 * should be a synchronous response that does not involve Kafka. For such requests, this REST controller exists.
 * 
 * @author Patrick.Doody
 * 
 */
@RestController
public class AccessController {

	@Value("${vcap.services.pz-geoserver-efs.credentials.postgres.hostname}")
	private String POSTGRES_HOST;
	@Value("${vcap.services.pz-geoserver-efs.credentials.postgres.port}")
	private String POSTGRES_PORT;
	@Value("${vcap.services.pz-geoserver-efs.credentials.postgres.database}")
	private String POSTGRES_DB_NAME;
	@Value("${vcap.services.pz-geoserver-efs.credentials.postgres.username}")
	private String POSTGRES_USER;
	@Value("${vcap.services.pz-geoserver-efs.credentials.postgres.password}")
	private String POSTGRES_PASSWORD;
	@Value("${postgres.schema}")
	private String POSTGRES_SCHEMA;

	@Autowired
	private AccessThreadManager threadManager;
	@Autowired
	private PiazzaLogger logger;
	@Autowired
	private Accessor accessor;
	@Autowired
	private Deployer deployer;
	@Autowired
	private GroupDeployer groupDeployer;
	@Autowired
	private Leaser leaser;
	@Autowired
	private ThreadPoolTaskExecutor threadPoolTaskExecutor;

	@Value("${vcap.services.pz-blobstore.credentials.access_key_id}")
	private String AMAZONS3_ACCESS_KEY;
	@Value("${vcap.services.pz-blobstore.credentials.secret_access_key}")
	private String AMAZONS3_PRIVATE_KEY;

	private static final String DEFAULT_PAGE_SIZE = "10";
	private static final String DEFAULT_PAGE = "0";
	private static final String DEFAULT_SORTBY = "dataId";
	private static final String DEFAULT_ORDER = "asc";

	/**
	 * Healthcheck required for all Piazza Core Services
	 * 
	 * @return String
	 */
	@RequestMapping(value = "/", method = RequestMethod.GET)
	public String getHealthCheck() {
		return "Hello, Health Check here for pz-access.";
	}

	/**
	 * Requests a file download that has been prepared by this Access component. This will return the raw bytes of the
	 * resource.
	 * 
	 * @param dataId
	 *            The Id of the Data Item to get. Assumes this file is ready to be downloaded.
	 */
	@RequestMapping(value = "/file/{dataId}", method = RequestMethod.GET)
	public ResponseEntity<?> accessFile(@PathVariable(value = "dataId") String dataId,
			@RequestParam(value = "fileName", required = false) String name) {
		try {
			// Get the DataResource item
			DataResource data = accessor.getData(dataId);
			String fileName = (StringUtils.isNullOrEmpty(name)) ? (dataId) : (name);

			if (data == null) {
				logger.log(String.format("Data not found for requested Id %s", dataId), PiazzaLogger.WARNING);
				return new ResponseEntity<PiazzaResponse>(new ErrorResponse(String.format("Data not found: %s", dataId), "Access"),
						HttpStatus.NOT_FOUND);
			}

			if (data.getDataType() instanceof TextDataType) {
				// Stream the Bytes back
				TextDataType textData = (TextDataType) data.getDataType();
				return getResponse(MediaType.TEXT_PLAIN, String.format("%s%s", fileName, ".txt"), textData.getContent().getBytes());
			} else if (data.getDataType() instanceof PostGISDataType) {
				// Obtain geoJSON from postGIS
				StringBuilder geoJSON = getPostGISGeoJSON(data);

				// Log the Request
				logger.log(String.format("Returning Bytes for %s of length %s", dataId, geoJSON.length()), PiazzaLogger.INFO);

				// Stream the Bytes back
				return getResponse(MediaType.TEXT_PLAIN, String.format("%s%s", fileName, ".geojson"), geoJSON.toString().getBytes());
			} else if (!(data.getDataType() instanceof FileRepresentation)) {
				String message = String.format("File download not available for Data Id %s; type is %s", dataId,
						data.getDataType().getClass().getSimpleName());
				logger.log(message, PiazzaLogger.WARNING);
				throw new Exception(message);
			} else {
				// Get the File Bytes from wherever the File Location
				FileAccessFactory fileFactory = new FileAccessFactory(AMAZONS3_ACCESS_KEY, AMAZONS3_PRIVATE_KEY);
				InputStream byteStream = fileFactory.getFile(((FileRepresentation) data.getDataType()).getLocation());
				byte[] bytes = StreamUtils.copyToByteArray(byteStream);

				// Log the Request
				logger.log(String.format("Returning Bytes for %s of length %s", dataId, bytes.length), PiazzaLogger.INFO);

				// Preserve the file extension from the original file.
				String originalFileName = ((FileRepresentation) data.getDataType()).getLocation().getFileName();
				String extension = FilenameUtils.getExtension(originalFileName);

				// Stream the Bytes back
				return getResponse(MediaType.APPLICATION_OCTET_STREAM, String.format("%s.%s", fileName, extension), bytes);
			}
		} catch (Exception exception) {
			exception.printStackTrace();
			logger.log(String.format("Error fetching Data %s: %s", dataId, exception.getMessage()), PiazzaLogger.ERROR);
			return new ResponseEntity<PiazzaResponse>(new ErrorResponse("Error fetching File: " + exception.getMessage(), "Access"),
					HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}

	/**
	 * Returns the Data resource object from the Resources collection.
	 * 
	 * @param dataId
	 *            Id of the Resource
	 * @return The resource matching the specified Id
	 */
	@RequestMapping(value = "/data/{dataId}", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<PiazzaResponse> getData(@PathVariable(value = "dataId") String dataId) {
		try {
			if (dataId.isEmpty()) {
				throw new Exception("No Data Id specified.");
			}
			// Query for the Data Id
			DataResource data = accessor.getData(dataId);
			if (data == null) {
				logger.log(String.format("Data not found for requested Id %s", dataId), PiazzaLogger.WARNING);
				return new ResponseEntity<PiazzaResponse>(new ErrorResponse(String.format("Data not found: %s", dataId), "Access"),
						HttpStatus.NOT_FOUND);
			}

			// Return the Data Resource item
			logger.log(String.format("Returning Data Metadata for %s", dataId), PiazzaLogger.INFO);
			return new ResponseEntity<PiazzaResponse>(new DataResourceResponse(data), HttpStatus.OK);
		} catch (Exception exception) {
			exception.printStackTrace();
			logger.log(String.format("Error fetching Data %s: %s", dataId, exception.getMessage()), PiazzaLogger.ERROR);
			return new ResponseEntity<PiazzaResponse>(new ErrorResponse("Error fetching Data: " + exception.getMessage(), "Access"),
					HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}

	/**
	 * Gets Deployment information for an active deployment, including URL and Data Id.
	 * 
	 * @see http://pz-swagger.stage.geointservices.io/#!/Deployment/ get_deployment_deploymentId
	 * 
	 * @param deploymentId
	 *            The Id of the deployment to fetch
	 * @return The deployment information, or an ErrorResponse if exceptions occur
	 */
	@RequestMapping(value = "/deployment/{deploymentId}", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<PiazzaResponse> getDeployment(@PathVariable(value = "deploymentId") String deploymentId) {
		try {
			if (deploymentId.isEmpty()) {
				throw new Exception("No Deployment Id specified.");
			}
			// Query for the Deployment Id
			Deployment deployment = accessor.getDeployment(deploymentId);
			if (deployment == null) {
				logger.log(String.format("Deployment not found for requested Id %s", deploymentId), PiazzaLogger.WARNING);
				return new ResponseEntity<PiazzaResponse>(
						new ErrorResponse(String.format("Deployment not found: %s", deploymentId), "Access"), HttpStatus.NOT_FOUND);
			}

			// Get the expiration date for this Deployment
			Lease lease = accessor.getDeploymentLease(deployment);
			String expiresOn = null;
			if (lease != null) {
				expiresOn = lease.getExpiresOn();
			}

			// Return the Data Resource item
			logger.log(String.format("Returning Deployment Metadata for %s", deploymentId), PiazzaLogger.INFO);
			return new ResponseEntity<PiazzaResponse>(new DeploymentResponse(deployment, expiresOn), HttpStatus.OK);
		} catch (Exception exception) {
			exception.printStackTrace();
			logger.log(String.format("Error fetching Deployment %s: %s", deploymentId, exception.getMessage()), PiazzaLogger.ERROR);
			return new ResponseEntity<PiazzaResponse>(new ErrorResponse("Error fetching Deployment: " + exception.getMessage(), "Access"),
					HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}

	/**
	 * Returns all Data held by the Piazza Ingest/Access components. This corresponds with the items in the Mongo
	 * db.Resources collection.
	 * 
	 * @return The list of all data held by the system.
	 */
	@RequestMapping(value = "/data", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<PiazzaResponse> getAllData(@RequestParam(value = "createdByJobId", required = false) String createdByJobId,
			@RequestParam(value = "page", required = false, defaultValue = DEFAULT_PAGE) Integer page,
			@RequestParam(value = "perPage", required = false, defaultValue = DEFAULT_PAGE_SIZE) Integer pageSize,
			@RequestParam(value = "sortBy", required = false, defaultValue = DEFAULT_SORTBY) String sortBy,
			@RequestParam(value = "order", required = false, defaultValue = DEFAULT_ORDER) String order,
			@RequestParam(value = "keyword", required = false) String keyword,
			@RequestParam(value = "userName", required = false) String userName) {
		try {
			// Don't allow for invalid orders
			if (!(order.equalsIgnoreCase("asc")) && !(order.equalsIgnoreCase("desc"))) {
				order = "asc";
			}
			return new ResponseEntity<PiazzaResponse>(
					accessor.getDataList(page, pageSize, sortBy, order, keyword, userName, createdByJobId), HttpStatus.OK);
		} catch (Exception exception) {
			logger.log(String.format("Error Querying Data: %s", exception.getMessage()), PiazzaLogger.ERROR);
			return new ResponseEntity<PiazzaResponse>(new ErrorResponse("Error Querying Data: " + exception.getMessage(), "Access"),
					HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}

	/**
	 * Returns all Deployments held by the Piazza Ingest/Access components. This corresponds with the items in the Mongo
	 * db.Deployments collection.
	 * 
	 * @return The list of all data held by the system.
	 */
	@RequestMapping(value = "/deployment", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<PiazzaResponse> getAllDeployments(
			@RequestParam(value = "page", required = false, defaultValue = DEFAULT_PAGE) Integer page,
			@RequestParam(value = "perPage", required = false, defaultValue = DEFAULT_PAGE_SIZE) Integer perPage,
			@RequestParam(value = "sortBy", required = false, defaultValue = DEFAULT_SORTBY) String sortBy,
			@RequestParam(value = "order", required = false, defaultValue = DEFAULT_ORDER) String order,
			@RequestParam(value = "keyword", required = false) String keyword) {
		try {
			// Don't allow for invalid orders
			if (!(order.equalsIgnoreCase("asc")) && !(order.equalsIgnoreCase("desc"))) {
				order = "asc";
			}
			return new ResponseEntity<PiazzaResponse>(accessor.getDeploymentList(page, perPage, sortBy, order, keyword), HttpStatus.OK);
		} catch (Exception exception) {
			logger.log(String.format("Error Querying Deployment: %s", exception.getMessage()), PiazzaLogger.ERROR);
			return new ResponseEntity<PiazzaResponse>(new ErrorResponse("Error Querying Deployment: " + exception.getMessage(), "Access"),
					HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}

	/**
	 * Returns the Number of Data Resources in the piazza system.
	 * 
	 * @return Number of Data items in the system.
	 */
	@RequestMapping(value = "/data/count", method = RequestMethod.GET)
	public long getDataCount() {
		return accessor.getDataCount();
	}

	/**
	 * Deletes Deployment information for an active deployment.
	 * 
	 * @param deploymentId
	 *            The Id of the deployment to delete.
	 * @param user
	 *            The user requesting the deployment information
	 * @return OK confirmation if deleted, or an ErrorResponse if exceptions occur
	 */
	@RequestMapping(value = "/deployment/{deploymentId}", method = RequestMethod.DELETE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<PiazzaResponse> deleteDeployment(@PathVariable(value = "deploymentId") String deploymentId, Principal user) {
		try {
			// Query for the Deployment Id
			Deployment deployment = accessor.getDeployment(deploymentId);
			if (deployment == null) {
				logger.log(String.format("Deployment not found for requested Id %s", deploymentId), PiazzaLogger.WARNING);
				return new ResponseEntity<PiazzaResponse>(
						new ErrorResponse(String.format("Deployment not found: %s", deploymentId), "Access"), HttpStatus.NOT_FOUND);
			}

			// Delete the Deployment
			deployer.undeploy(deploymentId);
			// Return OK
			return new ResponseEntity<PiazzaResponse>(
					new SuccessResponse("Deployment " + deploymentId + " was deleted successfully", "Access"), HttpStatus.OK);
		} catch (Exception exception) {
			exception.printStackTrace();
			String error = String.format("Error Deleting Deployment %s: %s", deploymentId, exception.getMessage());
			logger.log(error, PiazzaLogger.ERROR);
			return new ResponseEntity<PiazzaResponse>(new ErrorResponse(error, "Access"), HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}

	/**
	 * Creates a new Deployment Group in the Piazza database. No accompanying GeoServer Layer Group will be created yet
	 * at this point; however a placeholder GUID is associated with this Deployment Group that will be used as the title
	 * of the eventual GeoServer Layer Group.
	 * 
	 * @param createdBy
	 *            The user who requests the creation
	 * @return The Deployment Group Response
	 */
	@RequestMapping(value = "/deployment/group", method = RequestMethod.POST, produces = "application/json")
	public ResponseEntity<PiazzaResponse> createDeploymentGroup(@RequestParam(value = "createdBy", required = true) String createdBy) {
		try {
			// Create a new Deployment Group
			DeploymentGroup deploymentGroup = groupDeployer.createDeploymentGroup(createdBy);
			ResponseEntity<PiazzaResponse> response = new ResponseEntity<PiazzaResponse>(new DeploymentGroupResponse(deploymentGroup),
					HttpStatus.CREATED);
			return response;
		} catch (Exception exception) {
			// Log the error message.
			exception.printStackTrace();
			String error = String.format("Error Creating DeploymentGroup for user %s : %s", createdBy, exception.getMessage());
			logger.log(error, PiazzaLogger.ERROR);
			return new ResponseEntity<PiazzaResponse>(new ErrorResponse(error, "Access"), HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}

	/**
	 * Deletes a Deployment Group from Piazza, and from the corresponding GeoServer.
	 * 
	 * @param deploymentGroupId
	 *            The Id of the deployment Group to delete.
	 * @return Appropriate response
	 */
	@RequestMapping(value = "/deployment/group/{deploymentGroupId}", method = RequestMethod.DELETE, produces = "application/json")
	public ResponseEntity<PiazzaResponse> deleteDeploymentGroup(@PathVariable(value = "deploymentGroupId") String deploymentGroupId) {
		try {
			if ((deploymentGroupId == null) || (deploymentGroupId.isEmpty())) {
				return new ResponseEntity<PiazzaResponse>(new ErrorResponse("DeploymentGroup Id not specified.", "Access"),
						HttpStatus.BAD_REQUEST);
			}
			// Delete the DeploymentGroup from GeoServer, and remove it from
			// the Piazza DB persistence
			DeploymentGroup deploymentGroup = accessor.getDeploymentGroupById(deploymentGroupId);
			if (deploymentGroup == null) {
				return new ResponseEntity<PiazzaResponse>(new ErrorResponse("DeploymentGroup does not exist.", "Access"),
						HttpStatus.NOT_FOUND);
			}
			groupDeployer.deleteDeploymentGroup(deploymentGroup);
			return new ResponseEntity<PiazzaResponse>(new SuccessResponse("Group Deleted.", "Access"), HttpStatus.OK);
		} catch (HttpStatusCodeException httpException) {
			// Return the HTTP Error code from GeoServer
			String error = String.format("Could not delete DeploymentGroup. Response from GeoServer returned code %s with reason %s",
					httpException.getStatusCode().toString(), httpException.getMessage());
			logger.log(error, PiazzaLogger.ERROR);
			return new ResponseEntity<PiazzaResponse>(new ErrorResponse(error, "Access"), httpException.getStatusCode());
		} catch (Exception exception) {
			// Return the 500 Internal error
			String error = String.format("Could not delete DeploymentGroup. An unexpected error occurred: %s", exception.getMessage());
			logger.log(error, PiazzaLogger.ERROR);
			return new ResponseEntity<PiazzaResponse>(new ErrorResponse(error, "Access"), HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}

	/**
	 * Forces a check of all expired leases for reaping. Reaping will normally occur automatically every night. However,
	 * this endpoint provides a way to trigger at will.
	 */
	@RequestMapping(value = "/reap", method = RequestMethod.GET)
	public void forceReap() {
		leaser.reapExpiredLeases();
	}

	/**
	 * Returns administrative statistics for this component.
	 * 
	 * @return Component information
	 */
	@RequestMapping(value = "/admin/stats", method = RequestMethod.GET)
	public ResponseEntity<Map<String, Object>> getAdminStats() {
		Map<String, Object> stats = new HashMap<String, Object>();
		// Return information on the jobs currently being processed
		stats.put("jobs", threadManager.getRunningJobIds());
		stats.put("activeThreads", threadPoolTaskExecutor.getActiveCount());
		if (threadPoolTaskExecutor.getThreadPoolExecutor() != null) {
			stats.put("threadQueue", threadPoolTaskExecutor.getThreadPoolExecutor().getQueue().size());
		}
		return new ResponseEntity<Map<String, Object>>(stats, HttpStatus.OK);
	}

	/**
	 * @param type
	 *            MediaType to set http header content type
	 * @param fileName
	 *            file name to set for content disposition
	 * @param bytes
	 *            file bytes
	 * @return ResponseEntity
	 */
	private ResponseEntity<byte[]> getResponse(MediaType type, String fileName, byte[] bytes) {
		HttpHeaders header = new HttpHeaders();
		header.setContentType(type);
		header.set("Content-Disposition", "attachment; filename=" + fileName);
		header.setContentLength(bytes.length);
		return new ResponseEntity<byte[]>(bytes, header, HttpStatus.OK);
	}

	/**
	 * Gets the GeoJSON representation of a Data Resource currently stored in PostGIS.
	 * 
	 * @param data
	 *            DataResource object
	 * @return stringbuilder of geojson
	 * @throws Exception
	 */
	private StringBuilder getPostGISGeoJSON(DataResource data) throws Exception {
		// Connect to POSTGIS and gather geoJSON info
		DataStore postGisStore = accessor.getPostGisDataStore(POSTGRES_HOST, POSTGRES_PORT, POSTGRES_SCHEMA, POSTGRES_DB_NAME,
				POSTGRES_USER, POSTGRES_PASSWORD);

		PostGISDataType resource = (PostGISDataType) (data.getDataType());
		SimpleFeatureSource simpleFeatureSource = postGisStore.getFeatureSource(resource.getTable());
		SimpleFeatureCollection simpleFeatureCollection = simpleFeatureSource.getFeatures(Query.ALL);
		SimpleFeatureIterator simpleFeatureIterator = simpleFeatureCollection.features();

		StringBuilder geoJSON = new StringBuilder();
		try {
			while (simpleFeatureIterator.hasNext()) {
				SimpleFeature simpleFeature = simpleFeatureIterator.next();
				FeatureJSON featureJSON = new FeatureJSON();
				StringWriter writer = new StringWriter();

				featureJSON.writeFeature(simpleFeature, writer);
				String json = writer.toString();

				// Append each section
				geoJSON.append(json);
			}
		} finally {
			simpleFeatureIterator.close();
		}

		return geoJSON;
	}
}
