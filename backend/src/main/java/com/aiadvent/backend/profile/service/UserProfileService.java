package com.aiadvent.backend.profile.service;

import com.aiadvent.backend.profile.config.ProfileCacheProperties;
import com.aiadvent.backend.profile.domain.ProfileRole;
import com.aiadvent.backend.profile.domain.UserIdentity;
import com.aiadvent.backend.profile.domain.UserProfile;
import com.aiadvent.backend.profile.domain.UserProfileChannel;
import com.aiadvent.backend.profile.persistence.ProfileRoleRepository;
import com.aiadvent.backend.profile.persistence.UserIdentityRepository;
import com.aiadvent.backend.profile.persistence.UserProfileChannelRepository;
import com.aiadvent.backend.profile.persistence.UserProfileRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.lang.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class UserProfileService {

  private static final Logger log = LoggerFactory.getLogger(UserProfileService.class);
  private static final int MAX_IDENTITIES = 5;

  private final UserProfileRepository userProfileRepository;
  private final UserIdentityRepository userIdentityRepository;
  private final UserProfileChannelRepository channelRepository;
  private final ProfileRoleRepository profileRoleRepository;
  private final ProfileHandleService profileHandleService;
  private final Cache<String, UserProfileDocument> localCache;
  private final StringRedisTemplate redisTemplate;
  private final ValueOperations<String, String> redisOps;
  private final ObjectMapper objectMapper;
  private final ProfileCacheProperties cacheProperties;
  private final ProfileChangePublisher changePublisher;
  private final ProfileEventLogger profileEventLogger;
  private final MeterRegistry meterRegistry;
  private final Timer profileResolveTimer;
  private final Counter cacheHitCounter;
  private final Counter cacheMissCounter;
  private final DistributionSummary identitySummary;

  public UserProfileService(
      UserProfileRepository userProfileRepository,
      UserIdentityRepository userIdentityRepository,
      UserProfileChannelRepository channelRepository,
      ProfileRoleRepository profileRoleRepository,
      ProfileHandleService profileHandleService,
      ProfileCacheProperties cacheProperties,
      ObjectMapper objectMapper,
      ProfileChangePublisher changePublisher,
      ProfileEventLogger profileEventLogger,
      MeterRegistry meterRegistry,
      @Nullable StringRedisTemplate redisTemplate) {
    this.userProfileRepository = userProfileRepository;
    this.userIdentityRepository = userIdentityRepository;
    this.channelRepository = channelRepository;
    this.profileRoleRepository = profileRoleRepository;
    this.profileHandleService = profileHandleService;
    this.cacheProperties = cacheProperties;
    this.objectMapper = objectMapper;
    this.changePublisher = changePublisher;
    this.profileEventLogger = profileEventLogger;
    this.meterRegistry = meterRegistry;
    this.redisTemplate = redisTemplate;
    this.redisOps = redisTemplate != null ? redisTemplate.opsForValue() : null;
    this.localCache =
        Caffeine.newBuilder()
            .maximumSize(cacheProperties.getMaximumSize())
            .expireAfterWrite(cacheProperties.getLocalTtl().toMillis(), TimeUnit.MILLISECONDS)
            .build();
    this.profileResolveTimer = meterRegistry.timer("profile_resolve_seconds");
    this.cacheHitCounter = meterRegistry.counter("user_profile_cache_hit_total");
    this.cacheMissCounter = meterRegistry.counter("user_profile_cache_miss_total");
    this.identitySummary = meterRegistry.summary("profile_identity_total");
  }

  @Transactional(readOnly = true)
  public UserProfileDocument resolveProfile(ProfileLookupKey key) {
    String cacheKey = key.cacheKey();
    Timer.Sample sample = Timer.start(meterRegistry);
    try {
      UserProfileDocument cached = localCache.getIfPresent(cacheKey);
      if (cached != null) {
        cacheHitCounter.increment();
        return cached;
      }
      UserProfileDocument redisCached = readFromRedis(cacheKey);
      if (redisCached != null) {
        cacheHitCounter.increment();
        localCache.put(cacheKey, redisCached);
        return redisCached;
      }

      cacheMissCounter.increment();
      UserProfile profile = resolveOrCreateEntity(key);
      UserProfileDocument document = rebuildDocument(profile);
      cacheSnapshot(cacheKey, document);
      return document;
    } finally {
      sample.stop(profileResolveTimer);
    }
  }

  @Transactional
  public UserProfileDocument updateProfile(
      ProfileLookupKey key, ProfileUpdateCommand command, String ifMatch) {
    UserProfile profile = resolveOrCreateEntity(key);
    if (StringUtils.hasText(ifMatch) && !matchesVersion(profile, ifMatch)) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.PRECONDITION_FAILED,
          "ETag does not match current profile version");
    }
    applyProfileUpdates(profile, command);
    userProfileRepository.save(profile);
    updateChannelOverrides(profile, command.channelOverrides());
    return refreshAndPublish(profile, key.normalizedChannel());
  }

  @Transactional
  public UserProfileDocument attachIdentity(ProfileLookupKey key, IdentityCommand command) {
    if (!StringUtils.hasText(command.provider()) || !StringUtils.hasText(command.externalId())) {
      throw new IllegalArgumentException("provider and externalId must not be empty");
    }
    UserProfile profile = resolveOrCreateEntity(key);

    userIdentityRepository
        .findByProviderAndExternalId(
            normalize(command.provider()), command.externalId().trim().toLowerCase(Locale.ROOT))
        .ifPresent(
            existing -> {
              if (!existing.getProfile().getId().equals(profile.getId())) {
                throw new IllegalStateException("Identity already attached to another profile");
              }
              throw new IllegalStateException("Identity already attached");
            });

    long identities = userIdentityRepository.countByProfile(profile);
    if (identities >= MAX_IDENTITIES) {
      throw new IllegalStateException("Maximum identities per profile exceeded");
    }

    UserIdentity identity = new UserIdentity();
    identity.setProfile(profile);
    identity.setProvider(normalize(command.provider()));
    identity.setExternalId(command.externalId().trim());
    identity.setAttributes(command.attributes());
    identity.setScopes(command.scopes());

    userIdentityRepository.save(identity);
    profileEventLogger.identityAttached(profile, identity, key.normalizedChannel());
    return refreshAndPublish(profile, key.normalizedChannel());
  }

  @Transactional
  public UserProfileDocument detachIdentity(
      ProfileLookupKey key, String provider, String externalId) {
    UserProfile profile = resolveOrCreateEntity(key);
    userIdentityRepository
        .findByProviderAndExternalId(normalize(provider), externalId.trim())
        .ifPresent(
            identity -> {
              if (!identity.getProfile().getId().equals(profile.getId())) {
                throw new IllegalStateException("Identity belongs to another profile");
              }
              userIdentityRepository.delete(identity);
              profileEventLogger.identityDetached(
                  profile, identity.getProvider(), identity.getExternalId(), key.normalizedChannel());
            });

    return refreshAndPublish(profile, key.normalizedChannel());
  }

  @Transactional(readOnly = true)
  public Optional<UserProfileDocument> findProfile(ProfileLookupKey key) {
    return Optional.ofNullable(localCache.getIfPresent(key.cacheKey()))
        .or(() -> Optional.ofNullable(readFromRedis(key.cacheKey())));
  }

  private UserProfile refreshEntity(UserProfile profile) {
    return userProfileRepository
        .findById(profile.getId())
        .orElseThrow(() -> new IllegalStateException("Profile not found: " + profile.getId()));
  }

  private UserProfileDocument refreshAndPublish(UserProfile profile, @Nullable String channel) {
    UserProfile reloaded = refreshEntity(profile);
    UserProfileDocument document = rebuildDocument(reloaded);
    cacheSnapshot(cacheKeyFromProfile(reloaded), document);
    changePublisher.publish(new ProfileChangedEvent(reloaded.getId(), reloaded.getUpdatedAt()));
    profileEventLogger.profileUpdated(reloaded, channel);
    return document;
  }

  private void applyProfileUpdates(UserProfile profile, ProfileUpdateCommand command) {
    if (command == null) {
      return;
    }
    if (StringUtils.hasText(command.displayName())) {
      profile.setDisplayName(command.displayName().trim());
    }
    if (StringUtils.hasText(command.locale())) {
      profile.setLocale(command.locale().trim());
    }
    if (StringUtils.hasText(command.timezone())) {
      profile.setTimezone(command.timezone().trim());
    }
    if (command.communicationMode() != null) {
      profile.setCommunicationMode(command.communicationMode());
    }
    if (command.habits() != null) {
      profile.setHabits(command.habits());
    }
    if (command.antiPatterns() != null) {
      profile.setAntiPatterns(command.antiPatterns());
    }
    if (command.workHours() != null) {
      profile.setWorkHours(command.workHours());
    }
    if (command.metadata() != null) {
      profile.setMetadata(command.metadata());
    }
  }

  private void updateChannelOverrides(
      UserProfile profile, @Nullable List<ProfileUpdateCommand.ChannelSettingsCommand> overrides) {
    if (overrides == null || overrides.isEmpty()) {
      return;
    }
    for (ProfileUpdateCommand.ChannelSettingsCommand channelCommand : overrides) {
      if (channelCommand == null || !StringUtils.hasText(channelCommand.channel())) {
        continue;
      }
      String normalized = channelCommand.channel().trim().toLowerCase(Locale.ROOT);
      UserProfileChannel channel =
          channelRepository
              .findByProfileAndChannel(profile, normalized)
              .orElseGet(
                  () -> {
                    UserProfileChannel created = new UserProfileChannel();
                    created.setProfile(profile);
                    created.setChannel(normalized);
                    return created;
                  });
      channel.setSettings(channelCommand.settings());
      channelRepository.save(channel);
    }
  }

  private UserProfileDocument rebuildDocument(UserProfile profile) {
    List<UserIdentity> identities = userIdentityRepository.findByProfile(profile);
    List<UserProfileChannel> channels = channelRepository.findByProfile(profile);
    List<String> roleCodes =
        profileRoleRepository.findByIdProfileId(profile.getId()).stream()
            .map(ProfileRole::getRole)
            .filter(role -> role != null && StringUtils.hasText(role.getCode()))
            .map(role -> role.getCode().toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableList());

    List<String> habits = profile.getHabits() != null ? List.copyOf(profile.getHabits()) : List.of();
    List<String> antiPatterns =
        profile.getAntiPatterns() != null ? List.copyOf(profile.getAntiPatterns()) : List.of();

    List<UserProfileDocument.UserIdentityDocument> identityDocuments =
        identities.stream()
            .map(
                identity ->
                    new UserProfileDocument.UserIdentityDocument(
                        identity.getProvider(),
                        identity.getExternalId(),
                        clone(identity.getAttributes()),
                        identity.getScopes() != null
                            ? List.copyOf(identity.getScopes())
                            : List.of()))
            .toList();

    List<UserProfileDocument.UserChannelDocument> channelDocuments =
        channels.stream()
            .map(
                channel ->
                    new UserProfileDocument.UserChannelDocument(
                        channel.getChannel(), clone(channel.getSettings())))
            .toList();
    identitySummary.record(identities.size());

    return new UserProfileDocument(
        profile.getId(),
        profile.getNamespace(),
        profile.getReference(),
        profile.getDisplayName(),
        profile.getLocale(),
        profile.getTimezone(),
        profile.getCommunicationMode(),
        habits,
        antiPatterns,
        clone(profile.getWorkHours()),
        clone(profile.getMetadata()),
        identityDocuments,
        channelDocuments,
        roleCodes,
        profile.getUpdatedAt(),
        profile.getVersion());
  }

  private JsonNode clone(JsonNode node) {
    return node != null ? node.deepCopy() : null;
  }

  private UserProfile resolveOrCreateEntity(ProfileLookupKey key) {
    Optional<UUID> profileId =
        profileHandleService.findProfileId(key.normalizedNamespace(), key.normalizedReference());
    if (profileId.isPresent()) {
      return userProfileRepository
          .findById(profileId.get())
          .orElseThrow(
              () -> new IllegalStateException("Profile lookup entry without entity: " + key));
    }
    return createProfile(key);
  }

  private UserProfile createProfile(ProfileLookupKey key) {
    UserProfile profile = new UserProfile();
    profile.setNamespace(key.normalizedNamespace());
    profile.setReference(key.normalizedReference());
    profile.setDisplayName(key.normalizedReference());
    profile.setLocale("en");
    profile.setTimezone("UTC");

    UserProfile saved = userProfileRepository.save(profile);
    try {
      profileHandleService.resolveOrCreateHandle(
          saved.getNamespace(), saved.getReference(), () -> saved);
    } catch (DataIntegrityViolationException duplicate) {
      log.debug("Profile handle existed concurrently, reloading profile for {}", key.cacheKey());
      return profileHandleService
          .findProfileId(saved.getNamespace(), saved.getReference())
          .flatMap(userProfileRepository::findById)
          .orElse(saved);
    }
    profileEventLogger.profileCreated(saved, key.normalizedChannel());
    return saved;
  }

  private void cacheSnapshot(String cacheKey, UserProfileDocument document) {
    localCache.put(cacheKey, document);
    writeToRedis(cacheKey, document);
  }

  private UserProfileDocument readFromRedis(String cacheKey) {
    if (redisOps == null) {
      return null;
    }
    String payload = redisOps.get(redisKey(cacheKey));
    if (!StringUtils.hasText(payload)) {
      return null;
    }
    try {
      return objectMapper.readValue(payload, UserProfileDocument.class);
    } catch (JsonProcessingException ex) {
      log.warn("Failed to deserialize profile cache payload: {}", ex.getMessage());
      redisTemplate.delete(redisKey(cacheKey));
      return null;
    }
  }

  private boolean matchesVersion(UserProfile profile, String ifMatch) {
    String trimmed = ifMatch.trim();
    if (trimmed.startsWith("W/")) {
      trimmed = trimmed.substring(2);
    }
    trimmed = trimmed.replace("\"", "");
    String expected = Long.toString(profile.getVersion());
    return expected.equals(trimmed);
  }

  private void writeToRedis(String cacheKey, UserProfileDocument document) {
    if (redisOps == null) {
      return;
    }
    try {
      String payload = objectMapper.writeValueAsString(document);
      Duration ttl = cacheProperties.getRedisTtl();
      if (ttl != null) {
        redisOps.set(redisKey(cacheKey), payload, ttl);
      } else {
        redisOps.set(redisKey(cacheKey), payload);
      }
    } catch (JsonProcessingException ex) {
      log.warn("Failed to serialize profile {} to redis cache", cacheKey, ex);
    }
  }

  private String redisKey(String cacheKey) {
    return cacheProperties.getRedisPrefix() + cacheKey;
  }

  private String cacheKeyFromProfile(UserProfile profile) {
    return profile.getNamespace() + ":" + profile.getReference();
  }

  private String normalize(String value) {
    if (!StringUtils.hasText(value)) {
      return value;
    }
    return value.trim().toLowerCase(Locale.ROOT);
  }
}
