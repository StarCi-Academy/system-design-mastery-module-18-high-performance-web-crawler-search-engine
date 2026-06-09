package com.starci.frontier;

import org.apache.catalina.connector.Connector;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TomcatConfig {

    /**
     * Allow encoded slashes (%2F) in path variables so {@code GET /api/frontier/seen/:url}
     * can receive a full URL value like {@code https%3A%2F%2Fcrawl.starci.test%2Fa}.
     * Tomcat rejects percent-encoded slashes by default (CVE-2007-0450 mitigation); this
     * is safe here because there is no reverse-proxy that normalises paths before Tomcat.
     */
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> allowEncodedSlashes() {
        return factory -> factory.addConnectorCustomizers(
            // "passthrough" tells Tomcat to pass %2F as-is to the servlet, not reject it.
            (Connector connector) -> connector.setEncodedSolidusHandling("passthrough")
        );
    }
}
