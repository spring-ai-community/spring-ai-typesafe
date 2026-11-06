/*
 * Copyright 2026 - 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springaicommunity.typesafe.response;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springaicommunity.typesafe.JsonContent;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

/**
 * Reads a score answer's {@code legend} into a map keyed by level, whichever of the two
 * shapes the server sent it in.
 *
 * <p>
 * Jev sends an object keyed by level, {@code {"0": "Calm", "1": "Frustrated"}}. Drex, a
 * compatible server, sends the labels as an array in level order,
 * {@code ["Calm", "Frustrated"]}. Jackson's map binding reads only the first, so without
 * this a score answer from Drex failed the whole response.
 *
 * @author Saruar Ryskaliyev
 */
class LegendDeserializer extends ValueDeserializer<Map<Integer, JsonContent>> {

	@Override
	public Map<Integer, JsonContent> deserialize(JsonParser parser, DeserializationContext context) {
		JsonNode node = context.readTree(parser);
		if (node.isArray()) {
			Map<Integer, JsonContent> legend = new LinkedHashMap<>();
			int level = 0;
			for (JsonNode label : node) {
				legend.put(level++, context.readTreeAsValue(label, JsonContent.class));
			}
			return legend;
		}
		return context.readTreeAsValue(node,
				context.getTypeFactory().constructMapType(LinkedHashMap.class, Integer.class, JsonContent.class));
	}

}
