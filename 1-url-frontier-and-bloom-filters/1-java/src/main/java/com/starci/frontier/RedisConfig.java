package com.starci.frontier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;

/**
 * Configures the Spring Data Redis connection to use Jedis instead of Lettuce.
 * Jedis returns BF.* integer/array replies as native Java Long/List — no output-type mismatch.
 * Lettuce's ByteArrayOutput throws UnsupportedOperationException on BF.EXISTS integer replies.
 */
@Configuration
public class RedisConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String host;

    @Value("${spring.data.redis.port:6379}")
    private int port;

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        // JedisConnectionFactory handles mixed-type replies from Redis module commands correctly.
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(host, port);
        return new JedisConnectionFactory(config);
    }
}
