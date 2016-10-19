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
package access.deploy.geoserver;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

/**
 * Represents the XML Model that GeoServer uses to transfer information regarding Layer Groups. This is used with the
 * GeoServer REST API to create or update Layer Groups.
 * 
 * @author Patrick.Doody
 *
 */
public class LayerGroupModel2 {
	public LayerGroup2 layerGroup = new LayerGroup2();

	public LayerGroupModel2() {

	}

	@JacksonXmlRootElement(localName = "layerGroup")
	public static class LayerGroup2 {
		@JacksonXmlProperty(isAttribute = false, localName = "name")
		public String name;
		public String mode = "SINGLE";

		@JacksonXmlElementWrapper(localName = "publishables")
		@JacksonXmlProperty(localName = "published")
		public List<GroupLayer2> published = new ArrayList<GroupLayer2>();

		@JacksonXmlElementWrapper(localName = "styles")
		@JacksonXmlProperty(localName = "style")
		public List<String> style = new ArrayList<String>();

		public LayerGroup2() {

		}
	}

	public static class GroupLayer2 {
		@JacksonXmlProperty(isAttribute = true)
		public String type = "layer";

		public String name;

		public GroupLayer2() {

		}
	}

}
