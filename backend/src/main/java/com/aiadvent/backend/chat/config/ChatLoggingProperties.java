package com.aiadvent.backend.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "app.chat.logging")
@Validated
public class ChatLoggingProperties {

  private Model model = new Model();
  private Tools tools = new Tools();

  public Model getModel() {
    return model;
  }

  public void setModel(Model model) {
    this.model = model;
  }

  public Tools getTools() {
    return tools;
  }

  public void setTools(Tools tools) {
    this.tools = tools;
  }

  public static class Model {
    /**
     * Whether chat prompt/completion logging is enabled.
     */
    private boolean enabled = false;

    /**
     * Whether completion text should be logged in addition to the prompt.
     */
    private boolean logCompletion = false;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public boolean isLogCompletion() {
      return logCompletion;
    }

    public void setLogCompletion(boolean logCompletion) {
      this.logCompletion = logCompletion;
    }
  }

  public static class Tools {
    /**
     * Whether tool input/output logging is enabled.
     */
    private boolean enabled = false;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }
  }
}
