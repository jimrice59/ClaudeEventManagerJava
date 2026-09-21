package com.eventmanager.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

@Configuration
public class RedisConfig {

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofHours(1))
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new GenericJackson2JsonRedisSerializer(redisObjectMapper())))
                .disableCachingNullValues();

        RedisCacheConfiguration thirtySeconds = defaultConfig.entryTtl(Duration.ofSeconds(30));
        RedisCacheConfiguration thirtyMinutes = defaultConfig.entryTtl(Duration.ofMinutes(30));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                // Short TTL: a live count queried straight from Postgres (see
                // TicketService#getNumAvailableTickets) — this cache only bounds how stale a
                // burst of reads can be, not a substitute for cache invalidation on ticket writes.
                .withCacheConfiguration("availableTicketCounts", defaultConfig.entryTtl(Duration.ofSeconds(5)))
                // List/search endpoints: none of these are evicted on writes (list invalidation
                // isn't implemented), so the TTL alone bounds staleness. getAllEvents changes most
                // often (any event create/update/delete) so it gets a much shorter TTL than the
                // filtered venue/performer/event lists below.
                .withCacheConfiguration("allEvents", thirtySeconds)
                .withCacheConfiguration("venuesByCity", thirtyMinutes)
                .withCacheConfiguration("performersByName", thirtyMinutes)
                .withCacheConfiguration("performersByGenre", thirtyMinutes)
                .withCacheConfiguration("eventsByVenue", thirtyMinutes)
                .build();
    }

    /**
     * Replicates GenericJackson2JsonRedisSerializer's own default ObjectMapper (all-field
     * visibility + default typing, so cached values deserialize back to their concrete DTO
     * class) plus JavaTimeModule, which that default is missing — without it, caching any DTO
     * with a LocalDateTime field (EventResponse.eventDate/createdAt/updatedAt,
     * TicketResponse.eventDate, ...) throws SerializationException on every write.
     */
    private ObjectMapper redisObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        mapper.activateDefaultTyping(mapper.getPolymorphicTypeValidator(),
                ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);
        return mapper;
    }
}
