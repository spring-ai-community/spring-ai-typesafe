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

import org.jspecify.annotations.Nullable;
import org.springaicommunity.typesafe.JsonContent;
import org.springaicommunity.typesafe.question.Question;
import org.springaicommunity.typesafe.response.Answer;

import org.springframework.util.Assert;

/**
 * A stronger judge that decides the criteria Jev was unsure about: accept when confident,
 * escalate when unsure.
 *
 * <p>
 * Jev's confidence marks where its errors are: a criterion whose probability does not
 * clearly support its verdict is far more often wrong than one that does. A
 * {@link JevJudge} built with {@link JevJudge.Builder#escalateTo(JevEscalation) escalateTo}
 * keeps Jev's verdict on every criterion it answered decisively and hands only the rest
 * here, typically to an LLM-as-a-judge such as {@link ChatModelEscalation}. The expensive
 * judge is paid for the uncertain criteria alone.
 *
 * <p>
 * The escalation answers the same question Jev was asked, in the same terms: a
 * {@link org.springaicommunity.typesafe.response.NoulAnswer} for a noul, a
 * {@link org.springaicommunity.typesafe.response.ChoiceAnswer} for a choice, a
 * {@link org.springaicommunity.typesafe.response.ScoreAnswer} for a score. The judge then
 * applies the criterion's own pass rule to it, so a criterion keeps one definition of
 * passing whoever answers it.
 *
 * @author Christian Tzolov
 * @see JevJudge.Builder#escalateTo(JevEscalation, double)
 */
@FunctionalInterface
public interface JevEscalation {

	/**
	 * Answers one question Jev left undecided.
	 * @param name the criterion's name
	 * @param question the question Jev was asked
	 * @param state the state Jev judged
	 * @return the decision; throwing leaves Jev's own finding in place
	 */
	Decision decide(String name, Question question, JsonContent state);

	/**
	 * A stronger judge's answer to one question.
	 *
	 * @param answer the answer, of the same kind as the question
	 * @param reason why, in the judge's own words; appended to the feedback when the
	 * criterion fails, {@code null} when the judge gave none
	 */
	record Decision(Answer answer, @Nullable String reason) {

		public Decision {
			Assert.notNull(answer, "answer must not be null");
		}

	}

}
