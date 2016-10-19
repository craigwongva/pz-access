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
package access.database;

import java.net.UnknownHostException;
import java.util.List;
import java.util.regex.Pattern;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.geotools.data.DataStore;
import org.mongojack.DBCursor;
import org.mongojack.DBQuery;
import org.mongojack.DBSort;
import org.mongojack.DBUpdate;
import org.mongojack.JacksonDBCollection;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.mongodb.BasicDBObject;
import com.mongodb.DBCollection;
import com.mongodb.MongoClient;
import com.mongodb.MongoClientURI;
import com.mongodb.MongoException;
import com.mongodb.MongoTimeoutException;

import model.data.DataResource;
import model.data.deployment.Deployment;
import model.data.deployment.DeploymentGroup;
import model.data.deployment.Lease;
import model.response.DataResourceListResponse;
import model.response.DeploymentListResponse;
import model.response.Pagination;
import util.GeoToolsUtil;

/**
 * Handles Mongo access for the Deployer and the Leaser, and for the Resource collection which stores the Ingested
 * Resource metadata.
 * 
 * Also abstracts out some PostGIS accessor methods.
 * 
 * Deployments and leases have their own collections, and are managed by this Access component.
 * 
 * @author Patrick.Doody
 * 
 */
@Component
public class Accessor {
	@Value("${vcap.services.pz-mongodb.credentials.uri}")
	private String DATABASE_URI;
	@Value("${vcap.services.pz-mongodb.credentials.database}")
	private String DATABASE_NAME;
	@Value("${mongo.db.collection.resources}")
	private String RESOURCE_COLLECTION_NAME;
	@Value("${mongo.db.collection.deployments}")
	private String DEPLOYMENT_COLLECTION_NAME;
	@Value("${mongo.db.collection.deployment.groups}")
	private String DEPLOYMENT_GROUP_COLLECTION_NAME;
	@Value("${mongo.db.collection.leases}")
	private String LEASE_COLLECTION_NAME;
	@Value("${mongo.thread.multiplier}")
	private int mongoThreadMultiplier;
	private MongoClient mongoClient;

	@PostConstruct
	private void initialize() {
		try {
			mongoClient = new MongoClient(new MongoClientURI(DATABASE_URI + "?waitQueueMultiple=" + mongoThreadMultiplier));
		} catch (UnknownHostException exception) {
			System.out.println("Error connecting to MongoDB Instance.");
			exception.printStackTrace();
		}
	}

	@PreDestroy
	private void close() {
		mongoClient.close();
	}

	/**
	 * Gets a reference to the MongoDB Client Object.
	 * 
	 * @return
	 */
	public MongoClient getClient() {
		return mongoClient;
	}

	/**
	 * Gets the PostGIS data store for GeoTools.
	 * 
	 * @return Data Store.
	 */
	public DataStore getPostGisDataStore(String host, String port, String schema, String dbName, String user, String password)
			throws Exception {
		return GeoToolsUtil.getPostGisDataStore(host, port, schema, dbName, user, password);
	}

	/**
	 * Gets the Deployment for the specified Resource Id
	 * 
	 * @param dataId
	 *            The Id of the DataResource to check for a Deployment
	 * @return The Deployment for the Resource, if any. Null, if none.
	 */
	public Deployment getDeploymentByDataId(String dataId) {
		BasicDBObject query = new BasicDBObject("dataId", dataId);
		Deployment deployment;

		try {
			deployment = getDeploymentCollection().findOne(query);
		} catch (MongoTimeoutException mte) {
			throw new MongoException("MongoDB instance not available.");
		}

		return deployment;
	}

	/**
	 * Gets the Deployment Group by its unique Id.
	 * 
	 * @param deploymentGroupId
	 *            The Id of the Deployment Group
	 * @return The Deployment Group
	 */
	public DeploymentGroup getDeploymentGroupById(String deploymentGroupId) {
		BasicDBObject query = new BasicDBObject("deploymentGroupId", deploymentGroupId);
		DeploymentGroup deploymentGroup;

		try {
			deploymentGroup = getDeploymentGroupCollection().findOne(query);
		} catch (MongoTimeoutException mte) {
			throw new MongoException("MongoDB instance not available.");
		}

		return deploymentGroup;
	}

	/**
	 * Deletes a deployment entirely from the database.
	 * 
	 * <p>
	 * If a lease exists for this deployment, then it is also removed from the database.
	 * </p>
	 * 
	 * <p>
	 * Note that this is only for database collections only. This does not actually remove the data from GeoServer. This
	 * is handled in the Deployer.
	 * </p>
	 * 
	 * @param deployment
	 *            The deployment to delete
	 */
	public void deleteDeployment(Deployment deployment) {
		// Delete the deployment
		getDeploymentCollection().remove(new BasicDBObject("deploymentId", deployment.getDeploymentId()));
		// If the deployment had a lease, then delete that too.
		Lease lease = getDeploymentLease(deployment);
		if (lease != null) {
			deleteLease(lease);
		}
	}

	/**
	 * Deletes a Deployment Group.
	 * 
	 * @param deploymentGroup
	 *            The group to delete.
	 */
	public void deleteDeploymentGroup(DeploymentGroup deploymentGroup) {
		getDeploymentGroupCollection().remove(new BasicDBObject("deploymentGroupId", deploymentGroup.deploymentGroupId));
	}

	/**
	 * Deletes a lease from the database.
	 * 
	 * <p>
	 * Note that this is only for database collections only. This does not actually remove the data from GeoServer. This
	 * is handled in the Deployer.
	 * </p>
	 * 
	 * @param lease
	 *            The lease to delete.
	 */
	private void deleteLease(Lease lease) {
		getLeaseCollection().remove(new BasicDBObject("leaseId", lease.getLeaseId()));
	}

	/**
	 * Gets the Lease for the Deployment, if on exists.
	 * 
	 * @param deployment
	 *            The Deployment
	 * @return The Lease for the Deployment, if it exists. Null if not.
	 */
	public Lease getDeploymentLease(Deployment deployment) {
		BasicDBObject query = new BasicDBObject("deploymentId", deployment.getDeploymentId());
		Lease lease;

		try {
			lease = getLeaseCollection().findOne(query);
		} catch (MongoTimeoutException mte) {
			throw new MongoException("MongoDB instance not available.");
		}

		return lease;
	}

	/**
	 * Gets the DataResource from the Resources collection by Id. This Id is typically what will be returned to the user
	 * as the result of their Job.
	 * 
	 * These Ids are generated by the Ingest component upon ingest of the data. The Ingest component then updates the
	 * Job Manager with the Data Id, which is then sent back to the user. The user will then specify this Data Id in
	 * order to fetch their data.
	 * 
	 * @param dataId
	 *            The Id of the DataResource
	 * @return DataResource object
	 */
	public DataResource getData(String dataId) {
		BasicDBObject query = new BasicDBObject("dataId", dataId);
		DataResource data;

		try {
			if ((data = getDataResourceCollection().findOne(query)) == null) {
				return null;
			}
		} catch (MongoTimeoutException mte) {
			throw new MongoException("MongoDB instance not available.");
		}

		return data;
	}

	/**
	 * Gets a Deployment by its unique Id.
	 * 
	 * @param deploymentId
	 *            The deployment Id
	 * @return The Deployment
	 */
	public Deployment getDeployment(String deploymentId) {
		BasicDBObject query = new BasicDBObject("deploymentId", deploymentId);
		Deployment deployment;

		try {
			if ((deployment = getDeploymentCollection().findOne(query)) == null) {
				return null;
			}
		} catch (MongoTimeoutException mte) {
			throw new MongoException("MongoDB instance not available.");
		}

		return deployment;
	}

	/**
	 * Updates the Expiration date for the Lease.
	 * 
	 * @param leaseId
	 *            The Id of the lease to update.
	 * @param expirationDate
	 *            The new Expiration date. ISO8601 String.
	 */
	public void updateLeaseExpirationDate(String leaseId, String expirationDate) {
		getLeaseCollection().update(DBQuery.is("leaseId", leaseId), DBUpdate.set("expirationDate", expirationDate));
	}

	/**
	 * Updates the status of a Deployment Group to mark if an accompanying Layer Group in GeoServer has been created.
	 * 
	 * @param deploymentGroupId
	 *            The Id of the Deployment Group
	 * @param created
	 *            Whether or not the Deployment Group has an accompanying Layer Group in the GeoServer instance.
	 */
	public void updateDeploymentGroupCreated(String deploymentGroupId, boolean created) {
		getDeploymentGroupCollection().update(DBQuery.is("deploymentGroupId", deploymentGroupId),
				DBUpdate.set("hasGisServerLayer", created));
	}

	/**
	 * Creates a new Deployment entry in the database.
	 * 
	 * @param deployment
	 *            Deployment to enter
	 */
	public void insertDeployment(Deployment deployment) {
		getDeploymentCollection().insert(deployment);
	}

	/**
	 * Creates a new Deployment Group entry in the database.
	 * 
	 * @param deploymentGroup
	 *            Deployment Group to insert
	 */
	public void insertDeploymentGroup(DeploymentGroup deploymentGroup) {
		getDeploymentGroupCollection().insert(deploymentGroup);
	}

	/**
	 * Creates a new Lease entry in the database.
	 * 
	 * @param lease
	 *            Lease to enter
	 */
	public void insertLease(Lease lease) {
		getLeaseCollection().insert(lease);
	}

	/**
	 * Gets the Mongo Collection of all data currently referenced within Piazza.
	 * 
	 * @return Mongo collection for DataResources
	 */
	public JacksonDBCollection<DataResource, String> getDataResourceCollection() {
		DBCollection collection = mongoClient.getDB(DATABASE_NAME).getCollection(RESOURCE_COLLECTION_NAME);
		return JacksonDBCollection.wrap(collection, DataResource.class, String.class);
	}

	/**
	 * Gets a list of data from the database
	 * 
	 * @param page
	 *            The page number to start at
	 * @param pageSize
	 *            The number of results per page
	 * @param sortBy
	 *            The field to sort by
	 * @param order
	 *            The order "asc" or "desc"
	 * @param keyword
	 *            Keyword filtering
	 * @param userName
	 *            Username filtering
	 * @param createdByJobId
	 *            Filter by the ID of the Job that created this Data
	 * @return List of Data items
	 */
	public DataResourceListResponse getDataList(Integer page, Integer pageSize, String sortBy, String order, String keyword,
			String userName, String createdByJobId) throws Exception {
		Pattern regex = Pattern.compile(String.format("(?i)%s", keyword != null ? keyword : ""));
		// Get a DB Cursor to the query for general data
		DBCursor<DataResource> cursor = getDataResourceCollection().find().or(DBQuery.regex("metadata.name", regex),
				DBQuery.regex("metadata.description", regex));
		if ((userName != null) && !(userName.isEmpty())) {
			cursor.and(DBQuery.is("metadata.createdBy", userName));
		}
		if ((createdByJobId != null) && !(createdByJobId.isEmpty())) {
			cursor.and(DBQuery.is("metadata.createdByJobId", createdByJobId));
		}

		// Sort and order
		if (order.equalsIgnoreCase("asc")) {
			cursor = cursor.sort(DBSort.asc(sortBy));
		} else if (order.equalsIgnoreCase("desc")) {
			cursor = cursor.sort(DBSort.desc(sortBy));
		}

		Integer size = new Integer(cursor.size());
		// Filter the data by pages
		List<DataResource> data = cursor.skip(page * pageSize).limit(pageSize).toArray();
		// Attach pagination information
		Pagination pagination = new Pagination(size, page, pageSize, sortBy, order);
		// Create the Response and send back
		return new DataResourceListResponse(data, pagination);
	}

	/**
	 * Returns the number of items in the database for Data Resources
	 * 
	 * @return number of Data Resources in the database
	 */
	public long getDataCount() {
		return getDataResourceCollection().count();
	}

	/**
	 * Gets a list of deployments from the database
	 * 
	 * @param page
	 *            The page number to start
	 * @param pageSize
	 *            The number of results per page
	 * @param sortBy
	 *            The field to sort by
	 * @param order
	 *            The order "asc" or "desc"
	 * @param keyword
	 *            Keyword filtering
	 * @return List of deployments
	 */
	public DeploymentListResponse getDeploymentList(Integer page, Integer pageSize, String sortBy, String order, String keyword)
			throws Exception {
		Pattern regex = Pattern.compile(String.format("(?i)%s", keyword != null ? keyword : ""));
		// Get a DB Cursor to the query for general data
		DBCursor<Deployment> cursor = getDeploymentCollection().find().or(DBQuery.regex("deploymentId", regex),
				DBQuery.regex("dataId", regex), DBQuery.regex("capabilitiesUrl", regex));

		// Sort and order
		if (order.equalsIgnoreCase("asc")) {
			cursor = cursor.sort(DBSort.asc(sortBy));
		} else if (order.equalsIgnoreCase("desc")) {
			cursor = cursor.sort(DBSort.desc(sortBy));
		}

		Integer size = new Integer(cursor.size());
		// Filter the data by pages
		List<Deployment> data = cursor.skip(page * pageSize).limit(pageSize).toArray();
		// Attach pagination information
		Pagination pagination = new Pagination(size, page, pageSize, sortBy, order);
		// Create the Response and send back
		return new DeploymentListResponse(data, pagination);
	}

	/**
	 * Gets the Mongo Collection of all Deployments currently referenced within Piazza.
	 * 
	 * @return Mongo collection for Deployments
	 */
	public JacksonDBCollection<Deployment, String> getDeploymentCollection() {
		DBCollection collection = mongoClient.getDB(DATABASE_NAME).getCollection(DEPLOYMENT_COLLECTION_NAME);
		return JacksonDBCollection.wrap(collection, Deployment.class, String.class);
	}

	/**
	 * Gets the Mongo Collection of all Deployment Groups currently referenced within Piazza.
	 * 
	 * @return Mongo collection for Deployment Groups
	 */
	public JacksonDBCollection<DeploymentGroup, String> getDeploymentGroupCollection() {
		DBCollection collection = mongoClient.getDB(DATABASE_NAME).getCollection(DEPLOYMENT_GROUP_COLLECTION_NAME);
		return JacksonDBCollection.wrap(collection, DeploymentGroup.class, String.class);
	}

	/**
	 * Gets the Mongo Collection of all Leases currently referenced within Piazza.
	 * 
	 * @return Mongo collection for Leases
	 */
	public JacksonDBCollection<Lease, String> getLeaseCollection() {
		DBCollection collection = mongoClient.getDB(DATABASE_NAME).getCollection(LEASE_COLLECTION_NAME);
		return JacksonDBCollection.wrap(collection, Lease.class, String.class);
	}
}
