package com.eventledger.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class AccountClientConfig {

    /**
     * Built from the Boot-provided {@link RestClient.Builder}: that is what adds the
     * Micrometer observation, which creates the client span and injects the W3C
     * {@code traceparent} header on every outgoing call (trace propagation).
     */
    @Bean
    RestClient accountRestClient(RestClient.Builder builder,
                                 @Value("${account.service.url}") String baseUrl,
                                 @Value("${account.service.connect-timeout}") Duration connectTimeout,
                                 @Value("${account.service.read-timeout}") Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .version(HttpClient.Version.HTTP_1_1)   // no h2c upgrade dance with the internal Tomcat
                .build();
        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);
        return builder.baseUrl(baseUrl).requestFactory(factory).build();
    }
}
