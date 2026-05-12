package com.example.sftpadapter.config;

import com.example.sftpadapter.domain.SignalDataPair;
import com.example.sftpadapter.service.LocalFileMover;
import com.example.sftpadapter.service.SftpPairUploader;
import com.example.sftpadapter.service.SignalFileResolver;
import org.aopalliance.aop.Advice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.Pollers;
import org.springframework.integration.file.dsl.Files;
import org.springframework.integration.handler.advice.ErrorMessageSendingRecoverer;
import org.springframework.integration.handler.advice.RequestHandlerRetryAdvice;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.messaging.support.ErrorMessage;
import org.springframework.util.backoff.ExponentialBackOff;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

@Configuration
public class IntegrationConfig {

    private static final Logger log = LoggerFactory.getLogger(IntegrationConfig.class);
    static final String UPLOAD_ERROR_CHANNEL = "uploadErrorChannel";

    @Bean
    public IntegrationFlow signalInboxFlow(FileTransferProperties properties,
                                           SignalFileResolver resolver,
                                           SftpPairUploader uploader,
                                           LocalFileMover mover,
                                           @Qualifier(UPLOAD_ERROR_CHANNEL) MessageChannel uploadErrorChannel) {
        ensureLocalDirectoriesExist(properties);
        String pattern = "*" + properties.local().signalExtension();

        return IntegrationFlow.from(
                        Files.inboundAdapter(properties.local().inbox().toFile())
                                .patternFilter(pattern)
                                .preventDuplicates(true),
                        e -> e.poller(Pollers.fixedDelay(properties.poller().fixedDelay())
                                .maxMessagesPerPoll(properties.poller().maxMessagesPerPoll())))
                .handle(File.class, (signal, headers) -> uploader.upload(resolver.resolve(signal)),
                        e -> e.advice(uploadRetryAdvice(properties, uploadErrorChannel)))
                .handle(SignalDataPair.class, (pair, headers) -> {
                    mover.moveToProcessed(pair);
                    return null;
                })
                .get();
    }

    @Bean
    public IntegrationFlow uploadErrorFlow(LocalFileMover mover,
                                           @Qualifier(UPLOAD_ERROR_CHANNEL) MessageChannel uploadErrorChannel) {
        return IntegrationFlow.from(uploadErrorChannel)
                .handle(ErrorMessage.class, (errorMessage, headers) -> {
                    Object payload = errorMessage.getPayload();
                    File signal = extractSignalFile(payload);
                    if (signal == null) {
                        log.error("Upload error received without a recoverable signal file", (Throwable) payload);
                        return null;
                    }
                    log.error("Upload retries exhausted for {} - moving to error directory", signal.getName(),
                            (Throwable) payload);
                    mover.moveToError(signal);
                    return null;
                })
                .get();
    }

    @Bean(UPLOAD_ERROR_CHANNEL)
    public MessageChannel uploadErrorChannel() {
        return new DirectChannel();
    }

    private Advice uploadRetryAdvice(FileTransferProperties properties, MessageChannel uploadErrorChannel) {
        var backoff = new ExponentialBackOff();
        backoff.setInitialInterval(properties.retry().initialInterval().toMillis());
        backoff.setMultiplier(properties.retry().multiplier());
        backoff.setMaxInterval(properties.retry().maxInterval().toMillis());
        backoff.setMaxAttempts(Math.max(0, properties.retry().maxAttempts() - 1));

        RetryPolicy retryPolicy = RetryPolicy.builder()
                .backOff(backoff)
                .build();

        var advice = new RequestHandlerRetryAdvice();
        advice.setRetryPolicy(retryPolicy);
        advice.setRecoveryCallback(new ErrorMessageSendingRecoverer(uploadErrorChannel));
        return advice;
    }

    private File extractSignalFile(Object payload) {
        if (payload instanceof MessagingException me && me.getFailedMessage() != null
                && me.getFailedMessage().getPayload() instanceof File file) {
            return file;
        }
        return null;
    }

    private void ensureLocalDirectoriesExist(FileTransferProperties properties) {
        createDirectory(properties.local().inbox());
        createDirectory(properties.local().processed());
        createDirectory(properties.local().error());
    }

    private void createDirectory(Path dir) {
        try {
            java.nio.file.Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create directory " + dir, e);
        }
    }
}
