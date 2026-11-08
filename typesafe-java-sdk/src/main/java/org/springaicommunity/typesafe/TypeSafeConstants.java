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

package org.springaicommunity.typesafe;



import org.springaicommunity.typesafe.api.TypeSafeApi;

import java.time.Duration;

/**
 * Public environment variable names and client defaults, mirroring the
 * {@code typesafe_sdk.constants} module of the Python SDK so an application configured for
 * one SDK works with the other.
 *
 * @author Christian Tzolov
 */
public final class TypeSafeConstants {

	/** Environment variable holding the API key. */
	public static final String API_KEY_ENV = "TYPESAFE_API_KEY";

	/** Environment variable overriding the API root. */
	public static final String BASE_URL_ENV = "TYPESAFE_BASE_URL";

	/** Environment variable overriding the default model. */
	public static final String DEFAULT_MODEL_ENV = "TYPESAFE_DEFAULT_MODEL";

	/** The standard API root. */
	public static final String DEFAULT_BASE_URL = "https://api.typesafe.ai";

	/** The model used when none is configured. */
	public static final String DEFAULT_MODEL = TypeSafeModels.JEV_LATEST;

	/** Timeout applied to each HTTP operation when the SDK builds its own transport. */
	public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

	/** Default System One path, relative to the API root. */
	public static final String DEFAULT_SYSTEM_ONE_PATH = TypeSafeApi.DEFAULT_SYSTEM_ONE_PATH;

	/** Default model listing path, relative to the API root. */
	public static final String DEFAULT_MODELS_PATH = TypeSafeApi.DEFAULT_MODELS_PATH;

	private TypeSafeConstants() {
	}

}
