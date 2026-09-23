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

import org.springaicommunity.typesafe.response.ScoreAnswer;
import org.springaicommunity.typesafe.response.SystemOneResponse;

import org.springframework.util.Assert;

/**
 * Several score dimensions combined into one number with weights you control.
 *
 * <p>
 * <strong>For ordering, never for gating.</strong> The argument this SDK makes for Jev
 * over a single rubric prompt is that independent checks must be thresholded
 * independently: an answer that is fluent and on topic but quotes an impossible
 * temperature has to fail on plausibility alone, and any average hides exactly that. So
 * do not build a pass/fail decision on a composite — {@link JevJudge} thresholds each
 * criterion separately and is the right tool for that.
 *
 * <p>
 * What a composite is genuinely for is ranking a field of candidates that have all
 * already passed: which five of forty applicants to read first, which support ticket to
 * work next. There the dimensions are not pass conditions, they are preferences, and the
 * weights are the policy. Keeping them in code means a disappointing ranking is fixed by
 * changing a weight rather than by rewording a question.
 *
 * <pre>{@code
 * JevCompositeScore engineering = JevCompositeScore.builder()
 *     .weight("python_depth", 0.40d)
 *     .weight("system_design", 0.40d)
 *     .weight("team_leadership", 0.10d)
 *     .weight("generalist", 0.10d)
 *     .build();
 *
 * candidates.sort(comparingDouble(c -> -engineering.of(c.response())));
 * }</pre>
 *
 * @author Christian Tzolov
 */
public final class JevCompositeScore {

	private final Map<String, Double> weights;

	private JevCompositeScore(Map<String, Double> weights) {
		this.weights = weights;
	}

	/**
	 * Combines the named score answers of a response.
	 * @param response the response, which must carry a score answer for every weighted
	 * name
	 * @return the weighted sum of each dimension normalised to {@code [0, 1]}
	 */
	public double of(SystemOneResponse response) {
		Assert.notNull(response, "response must not be null");
		double total = 0.0d;
		for (Map.Entry<String, Double> entry : this.weights.entrySet()) {
			ScoreAnswer answer = response.score(entry.getKey());
			total += entry.getValue() * normalise(answer);
		}
		return total;
	}

	/**
	 * Normalises one score onto {@code [0, 1]} using the rubric's own height, so
	 * dimensions with different numbers of levels can be compared.
	 * @param answer the score answer
	 * @return the normalised value
	 * @throws IllegalStateException when the answer carries no legend, so the rubric has
	 * no height to divide by
	 */
	public static double normalise(ScoreAnswer answer) {
		Assert.notNull(answer, "answer must not be null");
		int maxLevel = answer.maxLevel();
		// The rubric height comes from the legend. Without it there is no scale to divide
		// by, and returning 0 would score every candidate identically — a
		// plausible-looking
		// number that silently collapses the ranking this class exists to produce.
		Assert.state(maxLevel > 0,
				"cannot normalise a score whose answer carries no legend, so the rubric height is unknown");
		return answer.value() / maxLevel;
	}

	/**
	 * @return the weight of each dimension, in declaration order
	 */
	public Map<String, Double> weights() {
		return this.weights;
	}

	/**
	 * @return a new builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for {@link JevCompositeScore}.
	 */
	public static final class Builder {

		private final Map<String, Double> weights = new LinkedHashMap<>();

		private boolean requireWeightsToSumToOne = true;

		private Builder() {
		}

		/**
		 * @param name the name of a score criterion
		 * @param weight its share of the composite
		 * @return this builder
		 */
		public Builder weight(String name, double weight) {
			Assert.hasText(name, "name must not be empty");
			Assert.isTrue(weight >= 0.0d, "weight must not be negative");
			this.weights.put(name, weight);
			return this;
		}

		/**
		 * Allows weights that do not sum to {@code 1}, which makes the composite's range
		 * something other than {@code [0, 1]}. Off by default, because weights that do
		 * not sum to one are usually a typo rather than an intention.
		 * @param allow whether to skip the check
		 * @return this builder
		 */
		public Builder allowUnnormalisedWeights(boolean allow) {
			this.requireWeightsToSumToOne = !allow;
			return this;
		}

		public JevCompositeScore build() {
			Assert.notEmpty(this.weights, "a composite must weight at least one dimension");
			if (this.requireWeightsToSumToOne) {
				double sum = this.weights.values().stream().mapToDouble(Double::doubleValue).sum();
				Assert.isTrue(Math.abs(sum - 1.0d) < 1e-9d, "weights must sum to 1 but summed to " + sum
						+ "; call allowUnnormalisedWeights(true) if that is deliberate");
			}
			return new JevCompositeScore(Collections.unmodifiableMap(new LinkedHashMap<>(this.weights)));
		}

	}

}
