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

package org.springaicommunity.typesafe.autoconfigure;



import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.api.TypeSafeApi;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * Auto-configures a {@link TypeSafeClient} from {@code spring.ai.typesafe.*}.
 *
 * <p>
 * The client is only created when the {@code api-key} property is defined, so an
 * application that does not configure TypeSafe starts normally. A key that is defined but
 * blank fails fast instead: a client that cannot authenticate is a misconfiguration better
 * met at startup than on the first call. Defining a {@code TypeSafeClient} bean of your own
 * switches this off entirely.
 *
 * @author Christian Tzolov
 */
@AutoConfiguration
@ConditionalOnClass({ TypeSafeClient.class, RestClient.class })
@ConditionalOnProperty(prefix = TypeSafeProperties.CONFIG_PREFIX, name = "api-key")
@EnableConfigurationProperties(TypeSafeProperties.class)
public class TypeSafeAutoConfiguration {

	/**
	 * Builds the client. The {@link RestClient.Builder} is taken from the context when one
	 * is available, so Boot's {@code RestClientCustomizer}s, interceptors and
	 * observability apply, and the configured timeout is layered on top of it.
	 * @param properties the configuration
	 * @param restClientBuilderProvider the context's builder, if any
	 * @return the client
	 */
	@Bean
	@ConditionalOnMissingBean
	public TypeSafeClient typeSafeClient(TypeSafeProperties properties, ObjectProvider<RestClient.Builder> restClientBuilderProvider) {

		// @ConditionalOnProperty matches a property that merely exists, so the very common
		// `api-key=${TYPESAFE_API_KEY:}` with the variable unset gets this far with a blank
		// key. Fail fast, naming the property, rather than build a client that cannot
		// authenticate.
		Assert.state(StringUtils.hasText(properties.getApiKey()),
				() -> "No API key configured. Set " + TypeSafeProperties.CONFIG_PREFIX + ".api-key.");

		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
		requestFactory.setReadTimeout(properties.getTimeout());

		// clone(), because the context's builder may be a singleton shared with other
		// consumers; replacing its request factory in place would change their timeouts too.
		RestClient.Builder restClientBuilder = restClientBuilderProvider.getIfAvailable(RestClient::builder)
			.clone()
			.requestFactory(requestFactory);

		return TypeSafeClient.builder()
			.apiKey(properties.getApiKey())
			.baseUrl(properties.getBaseUrl())
			.defaultModel(properties.getModel())
			// Declared as well as applied to the transport: the client counts it against
			// RetryPolicy.totalTimeout when deciding whether another attempt fits, so
			// leaving it at the 10s default lets a call overrun its declared budget.
			.timeout(properties.getTimeout())
			.retryPolicy(properties.toRetryPolicy())
			.restClientBuilder(restClientBuilder)
			.build();
	}

	/**
	 * @return the default paths, exposed so an application can see what the client talks
	 * to without reaching into the SDK
	 */
	@Bean
	@ConditionalOnMissingBean
	public TypeSafeEndpoints typeSafeEndpoints() {
		return new TypeSafeEndpoints(TypeSafeApi.DEFAULT_SYSTEM_ONE_PATH, TypeSafeApi.DEFAULT_MODELS_PATH);
	}

	/**
	 * The API paths in use.
	 *
	 * @param systemOnePath the evaluation endpoint path
	 * @param modelsPath the model listing endpoint path
	 */
	public record TypeSafeEndpoints(String systemOnePath, String modelsPath) {
	}

}
