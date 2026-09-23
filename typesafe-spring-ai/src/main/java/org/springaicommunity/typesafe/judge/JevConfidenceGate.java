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

package org.springaicommunity.typesafe.judge;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springaicommunity.typesafe.response.ChoiceAnswer;

import org.springframework.util.Assert;

/**
 * Confidence as a second decision axis: the answer says <em>what</em>, this says whether
 * to act on it unattended.
 *
 * <p>
 * Confidence is a statistic over the answer's own distribution, so a low value means the
 * options did not separate for this input — not that the answer is wrong. That makes it
 * the right signal for deciding how much autonomy to grant, and the wrong signal for
 * deciding correctness.
 *
 * <p>
 * A floor applies to every action. Above it, each action can demand more in proportion to
 * what it costs to get wrong: reading a balance is not transferring one.
 *
 * <pre>{@code
 * JevConfidenceGate gate = JevConfidenceGate.builder()
 *     .floor(0.6d)
 *     .require("transfer_funds", 0.85d)
 *     .build();
 *
 * switch (gate.decide(response.choice("intent"))) {
 *     case EXECUTE  -> handle(intent);
 *     case CONFIRM  -> askTheUserToConfirm(intent);
 *     case ESCALATE -> handOverToAHuman();
 * }
 * }</pre>
 *
 * @author Christian Tzolov
 */
public final class JevConfidenceGate {

	/** The confidence below which nothing is done unattended. */
	public static final double DEFAULT_FLOOR = 0.6d;

	private final double floor;

	private final Map<String, Double> required;

	private JevConfidenceGate(double floor, Map<String, Double> required) {
		this.floor = floor;
		this.required = required;
	}

	/**
	 * What to do with an answer, given how confident it is.
	 */
	public enum Decision {

		/** Confident enough for this action: act on it. */
		EXECUTE,

		/** Above the floor but short of what this action demands: ask the user first. */
		CONFIRM,

		/** Below the floor: nobody should act on this automatically. */
		ESCALATE

	}

	/**
	 * Decides on a choice, using the selected label as the action name.
	 * @param answer the choice answer
	 * @return the decision
	 */
	public Decision decide(ChoiceAnswer answer) {
		Assert.notNull(answer, "answer must not be null");
		return decide(answer.value(), answer.confidence());
	}

	/**
	 * Decides on a named action.
	 * @param action the action the answer selected
	 * @param confidence the confidence of the answer
	 * @return the decision
	 */
	public Decision decide(String action, double confidence) {
		Assert.hasText(action, "action must not be empty");
		if (confidence < this.floor) {
			return Decision.ESCALATE;
		}
		return confidence >= this.required.getOrDefault(action, this.floor) ? Decision.EXECUTE : Decision.CONFIRM;
	}

	/**
	 * @return the confidence below which every action escalates
	 */
	public double floor() {
		return this.floor;
	}

	/**
	 * @param action the action
	 * @return the confidence that action demands, which is the floor when none was
	 * declared
	 */
	public double requiredFor(String action) {
		return this.required.getOrDefault(action, this.floor);
	}

	/**
	 * @return a gate with the default floor and no action demanding more
	 */
	public static JevConfidenceGate withDefaultFloor() {
		return builder().build();
	}

	/**
	 * @return a new builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for {@link JevConfidenceGate}.
	 */
	public static final class Builder {

		private double floor = DEFAULT_FLOOR;

		private final Map<String, Double> required = new LinkedHashMap<>();

		private Builder() {
		}

		/**
		 * @param floor the confidence below which every action escalates
		 * @return this builder
		 */
		public Builder floor(double floor) {
			Assert.isTrue(floor >= 0.0d && floor <= 1.0d, "floor must be between 0 and 1");
			this.floor = floor;
			return this;
		}

		/**
		 * Demands more confidence for one action than the floor asks of the rest.
		 * @param action the action name, which for a choice is the option label
		 * @param confidence the confidence this action demands
		 * @return this builder
		 */
		public Builder require(String action, double confidence) {
			Assert.hasText(action, "action must not be empty");
			Assert.isTrue(confidence >= 0.0d && confidence <= 1.0d, "confidence must be between 0 and 1");
			this.required.put(action, confidence);
			return this;
		}

		public JevConfidenceGate build() {
			this.required.forEach((action, confidence) -> Assert.isTrue(confidence >= this.floor,
					"action '" + action + "' demands less confidence (" + confidence + ") than the floor (" + this.floor
							+ "), which it can never reach"));
			return new JevConfidenceGate(this.floor, Collections.unmodifiableMap(new LinkedHashMap<>(this.required)));
		}

	}

}
