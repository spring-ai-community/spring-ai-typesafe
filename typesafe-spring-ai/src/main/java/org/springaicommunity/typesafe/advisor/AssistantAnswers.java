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

package org.springaicommunity.typesafe.advisor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * Reads the model's answer out of a response, for the advisors to judge or screen.
 * <p>
 * A response can hold several generations: Anthropic with extended thinking returns
 * each thinking block as a generation of its own, ahead of the one that holds the
 * answer; Google GenAI returns one generation per part, thoughts flagged
 * {@code isThought}; and a provider asked for several choices returns them all, tagged
 * {@code index} (OpenAI) or {@code candidateIndex} (Google GenAI).
 *
 * @author Christian Tzolov
 */
final class AssistantAnswers {

	private AssistantAnswers() {
	}

	/**
	 * The answer to judge: the text of the first choice, thinking left out, its parts
	 * joined.
	 * @param response the response
	 * @return the answer text, empty when there is none
	 */
	static String answerOf(ChatClientResponse response) {
		return textsOf(response, false).stream().findFirst().orElse("");
	}

	/**
	 * Everything the caller can read, one text per choice (its parts joined) and one per
	 * thinking block, in the order they come. {@code ChatClient.content()} returns the
	 * first generation, which is the thinking when it is displayed, so each of them has
	 * to pass a screen. They are screened apart so that a long harmless text cannot
	 * dilute a short harmful one.
	 * @param response the response
	 * @return the texts, blank ones left out
	 */
	static List<String> visibleTextsOf(ChatClientResponse response) {
		return textsOf(response, true).stream().filter(StringUtils::hasText).toList();
	}

	private static List<String> textsOf(ChatClientResponse response, boolean withThinking) {
		if (response.chatResponse() == null || CollectionUtils.isEmpty(response.chatResponse().getResults())) {
			return List.of();
		}
		// Keyed by choice, or by the generation itself for a thinking block.
		Map<Object, StringBuilder> texts = new LinkedHashMap<>();
		for (Generation generation : response.chatResponse().getResults()) {
			if (isThinking(generation)) {
				if (withThinking) {
					texts.put(generation, new StringBuilder(textOf(generation)));
				}
			}
			else {
				texts.computeIfAbsent(new Choice(choiceOf(generation)), choice -> new StringBuilder())
					.append(textOf(generation));
			}
		}
		List<String> result = new ArrayList<>(texts.size());
		texts.values().forEach(text -> result.add(text.toString()));
		return result;
	}

	private static boolean isThinking(Generation generation) {
		Map<String, Object> properties = generation.getOutput().getMetadata();
		// Anthropic: signature (thinking), data (redacted thinking), thinking (streamed);
		// Google GenAI: isThought.
		return properties.containsKey("signature") || properties.containsKey("data")
				|| Boolean.TRUE.equals(properties.get("thinking")) || Boolean.TRUE.equals(properties.get("isThought"));
	}

	private static Object choiceOf(Generation generation) {
		Map<String, Object> properties = generation.getOutput().getMetadata();
		return properties.containsKey("candidateIndex") ? properties.get("candidateIndex") : properties.get("index");
	}

	private static String textOf(Generation generation) {
		String text = generation.getOutput().getText();
		return text == null ? "" : text;
	}

	private record Choice(Object index) {
	}

}
