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
package access.deploy;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;

import access.database.Accessor;
import access.deploy.geoserver.LayerGroupModel;
import access.deploy.geoserver.LayerGroupModel.GroupLayer;
import access.deploy.geoserver.LayerGroupModel.LayerGroup;
import access.deploy.geoserver.LayerGroupModel2;
import model.data.deployment.Deployment;
import model.data.deployment.DeploymentGroup;
import util.PiazzaLogger;
import util.UUIDFactory;

/**
 * Component that handles the deployment of Group Layers on GeoServer. This is done through the /deployment/group
 * endpoint. Group layers refer to a collection of layers and expose them all as a single WMS endpoint.
 * 
 * @author Patrick.Doody
 *
 */
@Component
public class GroupDeployer {
	@Autowired
	private PiazzaLogger logger;
	@Autowired
	private UUIDFactory uuidFactory;
	@Autowired
	private Accessor accessor;
	@Autowired
	private Deployer deployer;
	@Autowired
	private RestTemplate restTemplate;
	@Value("${vcap.services.pz-geoserver-efs.credentials.geoserver.hostname}")
	private String GEOSERVER_HOST;
	@Value("${vcap.services.pz-geoserver-efs.credentials.geoserver.port}")
	private String GEOSERVER_PORT;
	@Value("${vcap.services.pz-geoserver-efs.credentials.geoserver.username}")
	private String GEOSERVER_USERNAME;
	@Value("${vcap.services.pz-geoserver-efs.credentials.geoserver.password}")
	private String GEOSERVER_PASSWORD;

	/**
	 * Creates a new Deployment Group, without specifying any initial Data Layers to be added. This will create the
	 * DeploymentGroup and store it in the database, however, the actual GeoServer Layer Group will not be created. The
	 * GeoServer Layer Group will be created upon first request of a layer to be added to that group.
	 * 
	 * @param createdBy
	 *            The user who requests this creation
	 * 
	 * @return Deployment Group, containing an Id that can be used for future reference.
	 */
	public DeploymentGroup createDeploymentGroup(String createdBy) {
		// Commit the new group to the database and return immediately
		DeploymentGroup deploymentGroup = new DeploymentGroup(uuidFactory.getUUID(), createdBy);
		deploymentGroup.setHasGisServerLayer(false);
		accessor.insertDeploymentGroup(deploymentGroup);
		return deploymentGroup;
	}

	/**
	 * Creates a new Deployment Group. For each Deployment specified in the constructor, it will add that deployment's
	 * layer to the Layer Group.
	 * 
	 * @param deployments
	 *            The list of Layers to add to the group.
	 * @param createdBy
	 *            The user who requests this creation
	 * @return Deployment Group, containing an Id that can be used for future reference.
	 */
	public DeploymentGroup createDeploymentGroup(List<Deployment> deployments, String createdBy) throws Exception {
		// Create the Group.
		DeploymentGroup deploymentGroup = new DeploymentGroup(uuidFactory.getUUID(), createdBy);

		// Create the Layer Group Model to send to GeoServer
		LayerGroupModel layerGroupModel = new LayerGroupModel();
		layerGroupModel.layerGroup.name = deploymentGroup.deploymentGroupId;

		try {
			// For each Deployment, add a new group to the Layer Group Model.
			for (Deployment deployment : deployments) {
				GroupLayer groupLayer = new GroupLayer();
				groupLayer.name = deployment.getLayer();
				layerGroupModel.layerGroup.publishables.published.add(groupLayer);
			}
		} catch (Exception exception) {
			throw new Exception(String.format("Error Updating Deployments for Group Layer: %s", exception.getMessage()));
		}

		// Update the Layer Styles for each Layer in the Group
		updateLayerStyles(layerGroupModel);

		// Send the Layer Group creation request to GeoServer
		sendGeoServerLayerGroup(layerGroupModel, HttpMethod.POST);

		// Mark that the Layer has been created and commit to the database.
		deploymentGroup.setHasGisServerLayer(true);
		accessor.insertDeploymentGroup(deploymentGroup);

		// Return the Group
		return deploymentGroup;
	}

	/**
	 * Adds Layers to the GeoServer Layer Group.
	 * 
	 * <p>
	 * While the Deployment Group must exist at this point, the GeoServer Layer Group may or may not exist at this
	 * point. It will be created if not.
	 * </p>
	 * 
	 * @param deploymentGroup
	 *            The layer group to concatenate Layers to.
	 * @param deployments
	 *            The deployments to add to the Layer Group.
	 */
	public void updateDeploymentGroup(DeploymentGroup deploymentGroup, List<Deployment> deployments) throws Exception {
		// Check if the Layer Group exists. If it doesn't, then create it. If it
		// does, then Grab the Model to edit.
		LayerGroupModel layerGroupModel;
		if (deploymentGroup.getHasGisServerLayer() == false) {
			// Create the Layer Group Model to send to GeoServer
			layerGroupModel = new LayerGroupModel();
			layerGroupModel.layerGroup.name = deploymentGroup.deploymentGroupId;
		} else {
			// Get the existing Layer Group from GeoServer for edits.
			layerGroupModel = getLayerGroupFromGeoServer(deploymentGroup.deploymentGroupId);
		}

		try {
			// For each Deployment, add a new group to the Layer Group Model.
			for (Deployment deployment : deployments) {
				// Don't duplicate if it exists already.
				boolean exists = false;
				for (GroupLayer groupLayer : layerGroupModel.layerGroup.publishables.published) {
					if (groupLayer.name.equals(deployment.getLayer())) {
						exists = true;
					}
				}

				if (!exists) {
					// Add the new Layer
					GroupLayer groupLayer = new GroupLayer();
					groupLayer.name = deployment.getLayer();
					layerGroupModel.layerGroup.publishables.published.add(groupLayer);
				}
			}
		} catch (Exception exception) {
			throw new Exception(String.format("Error Updating Deployments for Group Layer: %s", exception.getMessage()));
		}

		// Balance the Styles and the Layers
		updateLayerStyles(layerGroupModel);

		// Send the Layer Group to GeoServer.
		HttpMethod method = deploymentGroup.getHasGisServerLayer() ? HttpMethod.PUT : HttpMethod.POST;
		sendGeoServerLayerGroup(layerGroupModel, method);

		// If it didn't exist before, mark that the Layer Group now exists.
		if (deploymentGroup.getHasGisServerLayer() == false) {
			accessor.updateDeploymentGroupCreated(deploymentGroup.deploymentGroupId, true);
		}
	}

	/**
	 * Deletes a Deployment Group. This will remove the corresponding Layer
	 * 
	 * @param deploymentGroup
	 */
	public void deleteDeploymentGroup(DeploymentGroup deploymentGroup) throws Exception {
		// Create Request
		HttpHeaders headers = deployer.getGeoServerHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		HttpEntity<String> request = new HttpEntity<String>(headers);
		String url = String.format("http://%s:%s/geoserver/rest/workspaces/piazza/layergroups/%s.json", GEOSERVER_HOST, GEOSERVER_PORT,
				deploymentGroup.deploymentGroupId);

		// Execute
		try {
			restTemplate.exchange(url, HttpMethod.DELETE, request, String.class);
		} catch (HttpClientErrorException | HttpServerErrorException exception) {
			// If the delete to GeoServer failed, then check why. Perhaps it's
			// already been deleted? It might not be an error we're concerned
			// with.
			if (exception.getStatusCode().equals(HttpStatus.NOT_FOUND)) {
				// If the Resource was deleted already, or doesn't exist - then
				// ignore this error.
			} else {
				throw new Exception(String.format("Could not delete Layer Group %s on GeoServer. Failed with Code %s : %s",
						deploymentGroup.deploymentGroupId, exception.getStatusCode().toString(), exception.getResponseBodyAsString()));
			}
		}

		// Remove the Deployment Group reference from Mongo
		accessor.deleteDeploymentGroup(deploymentGroup);
	}

	/**
	 * Gets the Layer Group Model from GeoServer for the Layer Group matching the specified Deployment Group Id. If this
	 * exists, it will return the model for the Layer Group.
	 * 
	 * <p>
	 * XML is used because GeoServer has an existing bug that prevents this GET request working with JSON when using
	 * Layer Groups above 5 Layers.
	 * </p>
	 * 
	 * @param deploymentGroupId
	 *            The Id of the layer group
	 * @return The Layer Group Model
	 */
	private LayerGroupModel getLayerGroupFromGeoServer(String deploymentGroupId) throws Exception {
		HttpHeaders headers = deployer.getGeoServerHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		HttpEntity<String> request = new HttpEntity<String>(headers);
		// Note that XML format is used. This is a work-around because JSON currently has a bug with GeoServer that
		// prevents a correct response from returning when Layer count is above 5.
		String url = String.format("http://%s:%s/geoserver/rest/workspaces/piazza/layergroups/%s.xml", GEOSERVER_HOST, GEOSERVER_PORT,
				deploymentGroupId);

		// Execute the request to get the Layer Group
		ResponseEntity<String> response;
		try {
			response = restTemplate.exchange(url, HttpMethod.GET, request, String.class);
		} catch (HttpStatusCodeException exception) {
			throw new Exception(String.format("Could not fetch Layer Group %s. Status code %s was returned by GeoServer with error: %s",
					deploymentGroupId, exception.getStatusCode().toString(), exception.getMessage()));
		}

		// Convert the GeoServer response into the Layer Group Model
		LayerGroupModel layerGroupJson = new LayerGroupModel();
		try {
			// Deserialize the XML response in the XML annotated Model
			ObjectMapper xmlMapper = new XmlMapper();
			xmlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, Boolean.FALSE);
			xmlMapper.configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, Boolean.TRUE);
			xmlMapper.configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, Boolean.TRUE);
			LayerGroupModel2.LayerGroup2 xmljsonModel = xmlMapper.readValue(response.getBody(), LayerGroupModel2.LayerGroup2.class);
			// Convert the XML annotated Model (used by responses) into the JSON annotated Model (used by requests)
			layerGroupJson.layerGroup.name = xmljsonModel.name;
			for (LayerGroupModel2.GroupLayer2 layer : xmljsonModel.published) {
				LayerGroupModel.GroupLayer groupLayer = new LayerGroupModel.GroupLayer();
				groupLayer.name = layer.name;
				layerGroupJson.layerGroup.publishables.published.add(groupLayer);
			}

			for (String style : xmljsonModel.style) {
				layerGroupJson.layerGroup.styles.style.add(style);
			}

		} catch (HttpClientErrorException | HttpServerErrorException exception) {
			throw new Exception(String.format(
					"Could not read in Layer Group from GeoServer response for %s: %s. Expected back a Layer Group description, but GeoServer responded with Code %s and Body %s",
					deploymentGroupId, exception.getMessage(), exception.getStatusCode().toString(), exception.getResponseBodyAsString()));
		} catch (Exception exception) {
			throw new Exception(String.format("Could not read in Layer Group from GeoServer response for %s: %s", deploymentGroupId,
					exception.getMessage()));
		}

		return layerGroupJson;
	}

	/**
	 * Sends a Layer Group to GeoServer. This will either update an existing layer group, or create a new one. The
	 * payload is exactly the same, however the HttpMethod will change from POST (create) to PUT (update).
	 * 
	 * @param layerGroup
	 *            The Layer Group to update.
	 * @param method
	 *            POST to create a new Layer Group, and PUT to update an existing one.
	 */
	private void sendGeoServerLayerGroup(LayerGroupModel layerGroup, HttpMethod method) throws Exception {
		// Create the Request
		HttpHeaders headers = deployer.getGeoServerHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		HttpEntity<String> request = null;
		String payload = null;
		try {
			payload = new ObjectMapper().writeValueAsString(layerGroup);
			request = new HttpEntity<String>(payload, headers);
		} catch (Exception exception) {
			throw new Exception(
					String.format("Error serializing Request Body to GeoServer for updating Layer Group: %s", exception.getMessage()));
		}
		String url = String.format(
				method.equals(HttpMethod.PUT) ? "http://%s:%s/geoserver/rest/workspaces/piazza/layergroups/%s.json"
						: "http://%s:%s/geoserver/rest/workspaces/piazza/layergroups.json",
				GEOSERVER_HOST, GEOSERVER_PORT, layerGroup.layerGroup.name);

		// Send
		ResponseEntity<String> response = null;
		try {
			response = restTemplate.exchange(url, method, request, String.class);
		} catch (HttpClientErrorException | HttpServerErrorException exception) {
			String error = String.format("Error sending Layer Group %s to GeoServer HTTP %s to %s. Server responded with: %s",
					layerGroup.layerGroup.name, method.toString(), url, exception.getResponseBodyAsString());
			logger.log(error, PiazzaLogger.ERROR);
			logger.log(String.format("Request Payload for failed request was: %s", payload), PiazzaLogger.ERROR);
			throw new Exception(error);
		}
		if (response.getStatusCode().equals(HttpStatus.CREATED) || (response.getStatusCode().equals(HttpStatus.OK))) {
			// Updated
		} else {
			throw new Exception(String.format("Could not update GeoServer Layer Group %s. Request returned Status %s : %s",
					layerGroup.layerGroup.name, response.getStatusCode().toString(), response.getBody()));
		}
	}

	/**
	 * Ensures that the number of Styles in the Layer Group will exactly match the number of Layers in that Layer Group.
	 * 
	 * <p>
	 * GeoServer, for whatever reason, requires there to be an equal number of styles defined in a Layer Group model as
	 * there are numbers of Layers in that Group. Even if you are using default styles. This function will create blank
	 * style references in the `layergroup.styles` JSON tag to ensure that GeoServer is satisfied with this input when
	 * making modifications to the Layer Group.
	 * </p>
	 * 
	 * <p>
	 * Default styles are annotated by an empty String; this is how GeoServer seems to operate. So in order to specify
	 * the default style, we simply insert an empty String into the styles list. If we ever want to apply custom layer
	 * styles for each layer, then this code will obviously need to be modified to do so by being more specific with the
	 * Style names in the Styles list.
	 * </p>
	 * 
	 * @param layerGroupModel
	 *            The Layer Group Model whose styles to balance
	 */
	private void updateLayerStyles(LayerGroupModel layerGroupModel) throws Exception {
		try {
			LayerGroup layerGroup = layerGroupModel.layerGroup;
			while (layerGroup.publishables.published.size() != layerGroup.styles.style.size()) {
				if (layerGroup.publishables.published.size() > layerGroup.styles.style.size()) {
					// Add Styles
					layerGroup.styles.style.add("");
				} else {
					// Remove Styles
					layerGroup.styles.style.remove(0);
				}
			}
		} catch (Exception exception) {
			throw new Exception(String.format("Error updating layer Styles for Deployments: %s", exception.getMessage()));
		}
	}
}
