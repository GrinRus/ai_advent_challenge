package com.aiadvent.backend.support;

import com.aiadvent.backend.chat.token.TokenUsageEstimator;
import com.aiadvent.backend.chat.token.TokenUsageEstimator.Estimate;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingResult;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;
import com.knuddels.jtokkit.api.ModelType;
import java.util.Optional;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class TestTokenUsageConfiguration {

  @Bean
  @Primary
  EncodingRegistry testEncodingRegistry() {
    return new EncodingRegistry() {
      private final Encoding encoding = new StubEncoding();

      @Override
      public Optional<Encoding> getEncoding(String encodingName) {
        return Optional.of(encoding);
      }

      @Override
      public Encoding getEncoding(EncodingType encodingType) {
        return encoding;
      }

      @Override
      public Optional<Encoding> getEncodingForModel(String modelName) {
        return Optional.of(encoding);
      }

      @Override
      public Encoding getEncodingForModel(ModelType modelType) {
        return encoding;
      }

      @Override
      public EncodingRegistry registerGptBytePairEncoding(
          com.knuddels.jtokkit.api.GptBytePairEncodingParams parameters) {
        return this;
      }

      @Override
      public EncodingRegistry registerCustomEncoding(Encoding encoding) {
        return this;
      }
    };
  }

  @Bean
  @Primary
  TokenUsageEstimator testTokenUsageEstimator() {
    return request -> {
      int prompt = length(request.prompt());
      int completion = length(request.completion());
      return new Estimate(prompt, completion, prompt + completion, false, false);
    };
  }

  private static int length(String value) {
    return value != null ? value.length() : 0;
  }

  private static final class StubEncoding implements Encoding {
    @Override
    public IntArrayList encode(String text) {
      throw new UnsupportedOperationException();
    }

    @Override
    public EncodingResult encode(String text, int maxTokens) {
      throw new UnsupportedOperationException();
    }

    @Override
    public IntArrayList encodeOrdinary(String text) {
      throw new UnsupportedOperationException();
    }

    @Override
    public EncodingResult encodeOrdinary(String text, int maxTokens) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int countTokens(String text) {
      return length(text);
    }

    @Override
    public int countTokensOrdinary(String text) {
      return length(text);
    }

    @Override
    public String decode(IntArrayList tokens) {
      throw new UnsupportedOperationException();
    }

    @Override
    public byte[] decodeBytes(IntArrayList tokens) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String getName() {
      return "stub-encoding";
    }
  }
}
