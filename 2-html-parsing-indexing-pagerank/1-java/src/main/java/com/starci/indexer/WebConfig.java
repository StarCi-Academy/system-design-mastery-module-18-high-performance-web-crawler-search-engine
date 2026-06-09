package com.starci.indexer;

import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Allow URL-encoded slashes (%2F) inside path variables so that
 * GET /api/indexer/rank/<url-encoded-url> works without HTTP 400.
 * Tomcat 10+ rejects %2F in path segments by default; setting
 * encodedSolidusHandling to "decode" on the connector passes
 * the encoded form through to the Spring MVC dispatcher.
 */
@Configuration
class WebConfig {

    /**
     * Customize the embedded Tomcat connector so %2F in path variables
     * is decoded and forwarded to @PathVariable instead of rejected at
     * the connector level with HTTP 400.
     */
    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> tomcatCustomizer() {
        return factory -> factory.addConnectorCustomizers(connector ->
                // "decode" tells Tomcat to accept and forward encoded slashes.
                connector.setEncodedSolidusHandling("decode")
        );
    }
}
