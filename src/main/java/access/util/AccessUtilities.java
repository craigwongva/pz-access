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
package access.util;

import org.apache.commons.io.IOUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import model.data.DataResource;
import model.data.location.FileAccessFactory;
import model.data.location.FileLocation;
import model.data.type.RasterDataType;

/**
 * Utility class to handle common functionality required by access components
 * 
 * @author Patrick.Doody
 *
 */
@Component
public class AccessUtilities {
	@Value("${vcap.services.pz-blobstore.credentials.access_key_id}")
	private String AMAZONS3_ACCESS_KEY;
	@Value("${vcap.services.pz-blobstore.credentials.secret_access_key}")
	private String AMAZONS3_PRIVATE_KEY;

	/**
	 * Gets the Bytes for a Data Resource
	 * 
	 * @param dataResource
	 *            The Data Resource
	 * @return The byte array for the file
	 */
	public byte[] getBytesForDataResource(DataResource dataResource) throws Exception {
		FileLocation fileLocation = ((RasterDataType) dataResource.getDataType()).getLocation();
		FileAccessFactory fileAccessFactory = new FileAccessFactory(AMAZONS3_ACCESS_KEY, AMAZONS3_PRIVATE_KEY);
		byte[] fileBytes = IOUtils.toByteArray(fileAccessFactory.getFile(fileLocation));
		return fileBytes;
	}
}
