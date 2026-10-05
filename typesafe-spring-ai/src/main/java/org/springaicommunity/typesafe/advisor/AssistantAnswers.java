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

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

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
		List<Generation> generations = generationsOf(response);
		List<Generation> answers = generations.stream().filter(generation -> !isThinking(generation)).toList();
		if (answers.isEmpty()) {
			return "";
		}
		Object firstChoice = choiceOf(answers.get(0));
		return answers.stream()
			.filter(generation -> Objects.equals(choiceOf(generation), firstChoice))
			.map(AssistantAnswers::textOf)
			.collect(Collectors.joining());
	}

	/**
	 * Everything the caller can read: the text of every generation, thinking and every
	 * choice included. {@code ChatClient.content()} returns the first generation, which
	 * is the thinking when it is displayed, so all of it has to pass a screen.
	 * @param response the response
	 * @return the text, empty when there is none
	 */
	static String visibleTextOf(ChatClientResponse response) {
		return generationsOf(response).stream()
			.map(AssistantAnswers::textOf)
			.filter(StringUtils::hasText)
			.collect(Collectors.joining(System.lineSeparator()));
	}

	private static List<Generation> generationsOf(ChatClientResponse response) {
		if (response.chatResponse() == null || CollectionUtils.isEmpty(response.chatResponse().getResults())) {
			return List.of();
		}
		return response.chatResponse().getResults();
	}

	private static boolean isThinking(Generation generation) {
		Map<String, Object> properties = generation.getOutput().getMetadata();
		return properties.containsKey("signature") || Boolean.TRUE.equals(properties.get("isThought"))
				|| Boolean.TRUE.equals(properties.get("thinking"));
	}

	private static Object choiceOf(Generation generation) {
		Map<String, Object> properties = generation.getOutput().getMetadata();
		return properties.containsKey("candidateIndex") ? properties.get("candidateIndex") : properties.get("index");
	}

	private static String textOf(Generation generation) {
		String text = generation.getOutput().getText();
		return text == null ? "" : text;
	}

}
