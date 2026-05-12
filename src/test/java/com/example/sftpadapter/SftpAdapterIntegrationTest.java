package com.example.sftpadapter;

import com.example.sftpadapter.config.FileTransferProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.integration.metadata.ConcurrentMetadataStore;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
class SftpAdapterIntegrationTest {

    @TempDir
    static Path workDir;

    @Container
    static final GenericContainer<?> SFTP = new GenericContainer<>(DockerImageName.parse("atmoz/sftp:latest"))
            .withExposedPorts(22)
            .withCommand("sftpuser:sftppass:::upload")
            .waitingFor(Wait.forListeningPort());

    @Container
    static final OracleContainer ORACLE = new OracleContainer(
            DockerImageName.parse("gvenzl/oracle-free:23-slim-faststart"))
            .withUsername("sftp_adapter")
            .withPassword("sftp_adapter")
            .withStartupTimeout(Duration.ofMinutes(5));

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", ORACLE::getJdbcUrl);
        registry.add("spring.datasource.username", ORACLE::getUsername);
        registry.add("spring.datasource.password", ORACLE::getPassword);
        registry.add("spring.datasource.driver-class-name", ORACLE::getDriverClassName);
        registry.add("sftp-adapter.local.inbox", () -> workDir.resolve("inbox").toString());
        registry.add("sftp-adapter.local.processed", () -> workDir.resolve("processed").toString());
        registry.add("sftp-adapter.local.error", () -> workDir.resolve("error").toString());
        registry.add("sftp-adapter.local.signal-extension", () -> ".signal");

        registry.add("sftp-adapter.remote.data-directory", () -> "/upload/data");
        registry.add("sftp-adapter.remote.signal-directory", () -> "/upload/signal");
        registry.add("sftp-adapter.remote.temporary-file-suffix", () -> ".writing");

        registry.add("sftp-adapter.sftp.host", SFTP::getHost);
        registry.add("sftp-adapter.sftp.port", () -> SFTP.getMappedPort(22));
        registry.add("sftp-adapter.sftp.username", () -> "sftpuser");
        registry.add("sftp-adapter.sftp.password", () -> "sftppass");
        registry.add("sftp-adapter.sftp.allow-unknown-keys", () -> "true");

        registry.add("sftp-adapter.poller.fixed-delay", () -> "500ms");
        registry.add("sftp-adapter.poller.max-messages-per-poll", () -> "5");

        registry.add("sftp-adapter.retry.max-attempts", () -> "2");
        registry.add("sftp-adapter.retry.initial-interval", () -> "100ms");
        registry.add("sftp-adapter.retry.multiplier", () -> "2.0");
        registry.add("sftp-adapter.retry.max-interval", () -> "500ms");
    }

    @Autowired
    private FileTransferProperties properties;

    @Autowired
    private ConcurrentMetadataStore metadataStore;

    @BeforeEach
    void prepareDirectories() throws IOException {
        Files.createDirectories(properties.local().inbox());
        Files.createDirectories(properties.local().processed());
        Files.createDirectories(properties.local().error());
    }

    @AfterEach
    void cleanupInbox() throws IOException {
        clear(properties.local().inbox());
    }

    @Test
    void uploadsDataThenSignalToSftp_andMovesFilesLocally() throws Exception {
        Path inbox = properties.local().inbox();
        String dataName = "orders-001.dat";
        String signalName = "orders-001.signal";

        Files.writeString(inbox.resolve(dataName), "id,total\n1,100.00\n2,75.50\n");
        Files.writeString(inbox.resolve(signalName), dataName);

        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(250)).untilAsserted(() -> {
            assertThat(Files.exists(inbox.resolve(dataName))).isFalse();
            assertThat(Files.exists(inbox.resolve(signalName))).isFalse();
            assertThat(remoteFileExists("/home/sftpuser/upload/data/" + dataName)).isTrue();
            assertThat(remoteFileExists("/home/sftpuser/upload/signal/" + signalName)).isTrue();
            assertThat(containsBothFiles(properties.local().processed(), dataName, signalName)).isTrue();
        });

        String dedupeKey = properties.metadataStore().inboxKeyPrefix()
                + inbox.resolve(signalName).toAbsolutePath();
        assertThat(metadataStore.get(dedupeKey))
                .as("persistent dedupe entry for processed signal")
                .isNotNull();
    }

    @Test
    void movesSignalToError_whenReferencedDataFileMissing() throws Exception {
        Path inbox = properties.local().inbox();
        String signalName = "lonely.signal";
        Files.writeString(inbox.resolve(signalName), "does-not-exist.dat");

        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(250)).untilAsserted(() -> {
            assertThat(Files.exists(inbox.resolve(signalName))).isFalse();
            assertThat(containsFile(properties.local().error(), signalName)).isTrue();
        });
    }

    private boolean remoteFileExists(String remotePath) throws Exception {
        var result = SFTP.execInContainer("test", "-f", remotePath);
        return result.getExitCode() == 0;
    }

    private boolean containsFile(Path root, String fileName) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.anyMatch(p -> p.getFileName().toString().equals(fileName));
        }
    }

    private boolean containsBothFiles(Path root, String first, String second) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            List<String> names = walk.map(p -> p.getFileName().toString()).toList();
            return names.contains(first) && names.contains(second);
        }
    }

    private void clear(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> entries = Files.list(dir)) {
            for (Path entry : entries.toList()) {
                if (Files.isDirectory(entry)) {
                    clear(entry);
                }
                Files.deleteIfExists(entry);
            }
        }
    }
}
