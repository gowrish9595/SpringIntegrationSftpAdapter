package com.example.sftpadapter.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "sftp-adapter")
public record FileTransferProperties(
        @NotNull Local local,
        @NotNull Remote remote,
        @NotNull Sftp sftp,
        @NotNull Poller poller,
        @NotNull Retry retry,
        @NotNull MetadataStore metadataStore
) {

    public record MetadataStore(@NotBlank String region, @NotBlank String inboxKeyPrefix) {}

    public record Local(
            @NotNull Path inbox,
            @NotNull Path processed,
            @NotNull Path error,
            @NotBlank String signalExtension
    ) {}

    public record Remote(
            @NotBlank String dataDirectory,
            @NotBlank String signalDirectory,
            @NotBlank String temporaryFileSuffix
    ) {}

    public record Sftp(
            @NotBlank String host,
            int port,
            @NotBlank String username,
            String password,
            String privateKeyLocation,
            String privateKeyPassphrase,
            boolean allowUnknownKeys
    ) {}

    public record Poller(@NotNull Duration fixedDelay, int maxMessagesPerPoll) {}

    public record Retry(int maxAttempts, @NotNull Duration initialInterval, double multiplier, @NotNull Duration maxInterval) {}
}
