package com.aiadvent.backend.support;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

public final class SingletonPostgresContainer
    extends PostgreSQLContainer<SingletonPostgresContainer> {

  private static final DockerImageName IMAGE =
      DockerImageName.parse("pgvector/pgvector:pg15").asCompatibleSubstituteFor("postgres");

  private static SingletonPostgresContainer container;

  private SingletonPostgresContainer() {
    super(IMAGE);
    withDatabaseName("ai_advent_test");
    withUsername("ai_advent");
    withPassword("ai_advent");
    withReuse(true);
  }

  public static synchronized SingletonPostgresContainer getInstance() {
    if (container == null) {
      container = new SingletonPostgresContainer();
      container.start();
    }
    return container;
  }
}
