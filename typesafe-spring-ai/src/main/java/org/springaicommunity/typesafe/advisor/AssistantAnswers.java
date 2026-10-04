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

import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.util.CollectionUtils;

/**
 * Reads the model's answer out of a response, for the advisors to judge or screen.
 *
 * @author Christian Tzolov
 */
final class AssistantAnswers {

	private AssistantAnswers() {
	}

	/**
	 * The text of the last generation. Anthropic with extended thinking returns each
	 * thinking block as a generation of its own, ahead of the one that holds the answer,
	 * so {@code getResult()} (the first) would hand over the reasoning, or an empty text
	 * when the thinking is not displayed.
	 * @param response the response
	 * @return the answer text, empty when there is none
	 */
	static String textOf(ChatClientResponse response) {
		if (response.chatResponse() == null) {
			return "";
		}
		List<Generation> generations = response.chatResponse().getResults();
		if (CollectionUtils.isEmpty(generations)) {
			return "";
		}
		String text = generations.get(generations.size() - 1).getOutput().getText();
		return text == null ? "" : text;
	}

}
