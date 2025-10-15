package com.aiadvent.backend.chat.support;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.template.TemplateRenderer;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.Resource;
import reactor.core.publisher.Flux;

@TestConfiguration
public class StubChatClientConfiguration {

  @Bean
  @Primary
  public ChatClient.Builder chatClientBuilder() {
    return new StubChatClientBuilder();
  }

  static class StubChatClientBuilder implements ChatClient.Builder {

    @Override
    public ChatClient build() {
      return new StubChatClient();
    }

    @Override
    public ChatClient.Builder defaultAdvisors(Advisor... advisors) {
      return this;
    }

    @Override
    public ChatClient.Builder defaultAdvisors(
        Consumer<ChatClient.AdvisorSpec> advisorSpecConsumer) {
      return this;
    }

    @Override
    public ChatClient.Builder defaultAdvisors(List<Advisor> advisors) {
      return this;
    }

    @Override
    public ChatClient.Builder defaultOptions(ChatOptions chatOptions) {
      return this;
    }

    @Override
    public ChatClient.Builder defaultUser(String text) {
      return this;
    }

    @Override
    public ChatClient.Builder defaultUser(Resource text, Charset charset) {
      return this;
    }

    @Override
    public ChatClient.Builder defaultUser(Resource text) {
      return this;
    }

    @Override
    public ChatClient.Builder defaultUser(Consumer<ChatClient.PromptUserSpec> userSpecConsumer) {
      return this;
    }

    @Override
    public ChatClient.Builder defaultSystem(String text) {
      return this;
    }

    @Override
    public ChatClient.Builder defaultSystem(Resource text, Charset charset) {
      return this;
    }

    @Override
    public ChatClient.Builder defaultSystem(Resource text) {
      return this;
    }

    @Override
    public ChatClient.Builder defaultSystem(
        Consumer<ChatClient.PromptSystemSpec> systemSpecConsumer) {
      return this;
    }

    @Override
    public ChatClient.Builder defaultTemplateRenderer(TemplateRenderer templateRenderer) {
      return this;
    }

    @Override
    public ChatClient.Builder defaultToolNames(String... toolNames) {
      return this;
    }

    @Override
    public ChatClient.Builder defaultTools(Object... toolObjects) {
      return this;
    }

    @Override
    public ChatClient.Builder defaultToolCallbacks(ToolCallback... toolCallbacks) {
      return this;
    }

    @Override
    public ChatClient.Builder defaultToolCallbacks(List<ToolCallback> toolCallbacks) {
      return this;
    }

    @Override
    public ChatClient.Builder defaultToolCallbacks(ToolCallbackProvider... toolCallbackProviders) {
      return this;
    }

    @Override
    public ChatClient.Builder defaultToolContext(Map<String, Object> toolContext) {
      return this;
    }

    @Override
    public ChatClient.Builder clone() {
      return this;
    }
  }

  static class StubChatClient implements ChatClient {

    @Override
    public ChatClientRequestSpec prompt() {
      throw new UnsupportedOperationException("Not required for stubbed ChatClient");
    }

    @Override
    public ChatClientRequestSpec prompt(String content) {
      return prompt(new Prompt(content));
    }

    @Override
    public ChatClientRequestSpec prompt(Prompt prompt) {
      StubChatClientState.capturePrompt(prompt);
      return new StubChatClientRequestSpec(StubChatClientState.responseFlux());
    }

    @Override
    public ChatClient.Builder mutate() {
      return new StubChatClientBuilder();
    }
  }

  static class StubChatClientRequestSpec implements ChatClient.ChatClientRequestSpec {

    private final Flux<org.springframework.ai.chat.model.ChatResponse> responseFlux;

    StubChatClientRequestSpec(Flux<org.springframework.ai.chat.model.ChatResponse> responseFlux) {
      this.responseFlux = responseFlux;
    }

    @Override
    public ChatClient.Builder mutate() {
      return new StubChatClientBuilder();
    }

    @Override
    public ChatClient.ChatClientRequestSpec advisors(Consumer<ChatClient.AdvisorSpec> consumer) {
      return this;
    }

    @Override
    public ChatClient.ChatClientRequestSpec advisors(Advisor... advisors) {
      return this;
    }

    @Override
    public ChatClient.ChatClientRequestSpec advisors(List<Advisor> advisors) {
      return this;
    }

    @Override
    public ChatClient.ChatClientRequestSpec messages(Message... messages) {
      return this;
    }

    @Override
    public ChatClient.ChatClientRequestSpec messages(List<Message> messages) {
      return this;
    }

    @Override
    public <T extends ChatOptions> ChatClient.ChatClientRequestSpec options(T options) {
      return this;
    }

    @Override
    public ChatClient.ChatClientRequestSpec toolNames(String... toolNames) {
      return this;
    }

    @Override
    public ChatClient.ChatClientRequestSpec tools(Object... toolObjects) {
      return this;
    }

    @Override
    public ChatClient.ChatClientRequestSpec toolCallbacks(ToolCallback... toolCallbacks) {
      return this;
    }

    @Override
    public ChatClient.ChatClientRequestSpec toolCallbacks(List<ToolCallback> toolCallbacks) {
      return this;
    }

    @Override
    public ChatClient.ChatClientRequestSpec toolCallbacks(
        ToolCallbackProvider... toolCallbackProviders) {
      return this;
    }

    @Override
    public ChatClient.ChatClientRequestSpec toolContext(Map<String, Object> toolContext) {
      return this;
    }

    @Override
    public ChatClient.ChatClientRequestSpec system(String text) {
      return this;
    }

    @Override
    public ChatClient.ChatClientRequestSpec system(Resource textResource, Charset charset) {
      return this;
    }

    @Override
    public ChatClient.ChatClientRequestSpec system(Resource text) {
      return this;
    }

    @Override
    public ChatClient.ChatClientRequestSpec system(Consumer<ChatClient.PromptSystemSpec> consumer) {
      return this;
    }

    @Override
    public ChatClient.ChatClientRequestSpec user(String text) {
      return this;
    }

    @Override
    public ChatClient.ChatClientRequestSpec user(Resource text, Charset charset) {
      return this;
    }

    @Override
    public ChatClient.ChatClientRequestSpec user(Resource text) {
      return this;
    }

    @Override
    public ChatClient.ChatClientRequestSpec user(Consumer<ChatClient.PromptUserSpec> consumer) {
      return this;
    }

    @Override
    public ChatClient.ChatClientRequestSpec templateRenderer(TemplateRenderer templateRenderer) {
      return this;
    }

    @Override
    public ChatClient.CallResponseSpec call() {
      throw new UnsupportedOperationException("Synchronous call is not supported in stub");
    }

    @Override
    public ChatClient.StreamResponseSpec stream() {
      return new StubStreamResponseSpec(responseFlux);
    }
  }

  static class StubStreamResponseSpec implements ChatClient.StreamResponseSpec {

    private final Flux<org.springframework.ai.chat.model.ChatResponse> responseFlux;

    StubStreamResponseSpec(Flux<org.springframework.ai.chat.model.ChatResponse> responseFlux) {
      this.responseFlux = responseFlux;
    }

    @Override
    public Flux<org.springframework.ai.chat.client.ChatClientResponse> chatClientResponse() {
      throw new UnsupportedOperationException("ChatClientResponse stream is not required");
    }

    @Override
    public Flux<org.springframework.ai.chat.model.ChatResponse> chatResponse() {
      return responseFlux;
    }

    @Override
    public Flux<String> content() {
      return responseFlux.map(response -> response.getResult().getOutput().getText());
    }
  }
}
