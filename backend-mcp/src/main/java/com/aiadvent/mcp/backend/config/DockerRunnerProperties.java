package com.aiadvent.mcp.backend.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "docker.runner")
public class DockerRunnerProperties {

  private String dockerBinary = "docker";
  private String image = "aiadvent/mcp-gradle-runner:latest";
  private String workspaceRoot = "/var/tmp/aiadvent/mcp-workspaces";
  private String workspaceVolume;
  private String gradleCachePath = "/var/tmp/aiadvent/gradle-cache";
  private String gradleCacheVolume;
  private Duration timeout = Duration.ofMinutes(15);
  private long maxLogBytes = 512 * 1024;
  private boolean enableNetwork = false;
  private List<String> defaultArgs = new ArrayList<>();
  private Map<String, String> defaultEnv = Map.of("GRADLE_USER_HOME", "/gradle-cache");
  private double memoryLimitGb = 0;
  private double cpuLimit = 0;

  public String getDockerBinary() {
    return dockerBinary;
  }

  public void setDockerBinary(String dockerBinary) {
    this.dockerBinary = dockerBinary;
  }

  public String getImage() {
    return image;
  }

  public void setImage(String image) {
    this.image = image;
  }

  public String getWorkspaceRoot() {
    return workspaceRoot;
  }

  public void setWorkspaceRoot(String workspaceRoot) {
    this.workspaceRoot = workspaceRoot;
  }

  public String getGradleCachePath() {
    return gradleCachePath;
  }

  public void setGradleCachePath(String gradleCachePath) {
    this.gradleCachePath = gradleCachePath;
  }

  public Duration getTimeout() {
    return timeout;
  }

  public void setTimeout(Duration timeout) {
    this.timeout = timeout;
  }

  public long getMaxLogBytes() {
    return maxLogBytes;
  }

  public void setMaxLogBytes(long maxLogBytes) {
    this.maxLogBytes = maxLogBytes;
  }

  public boolean isEnableNetwork() {
    return enableNetwork;
  }

  public void setEnableNetwork(boolean enableNetwork) {
    this.enableNetwork = enableNetwork;
  }

  public List<String> getDefaultArgs() {
    return defaultArgs;
  }

  public void setDefaultArgs(List<String> defaultArgs) {
    this.defaultArgs = defaultArgs;
  }

  public Map<String, String> getDefaultEnv() {
    return defaultEnv;
  }

  public void setDefaultEnv(Map<String, String> defaultEnv) {
    this.defaultEnv = defaultEnv;
  }

  public double getMemoryLimitGb() {
    return memoryLimitGb;
  }

  public void setMemoryLimitGb(double memoryLimitGb) {
    this.memoryLimitGb = memoryLimitGb;
  }

  public double getCpuLimit() {
    return cpuLimit;
  }

  public void setCpuLimit(double cpuLimit) {
    this.cpuLimit = cpuLimit;
  }

  public Path workspaceRootPath() {
    return Path.of(workspaceRoot);
  }

  public String getWorkspaceVolume() {
    return workspaceVolume;
  }

  public void setWorkspaceVolume(String workspaceVolume) {
    this.workspaceVolume = workspaceVolume;
  }

  public String getGradleCacheVolume() {
    return gradleCacheVolume;
  }

  public void setGradleCacheVolume(String gradleCacheVolume) {
    this.gradleCacheVolume = gradleCacheVolume;
  }
}
