package com.example.sftpadapter.service;

import com.example.sftpadapter.config.FileTransferProperties;
import com.example.sftpadapter.domain.SignalDataPair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class LocalFileMover {

    private static final Logger log = LoggerFactory.getLogger(LocalFileMover.class);
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private final FileTransferProperties properties;

    public LocalFileMover(FileTransferProperties properties) {
        this.properties = properties;
    }

    public void moveToProcessed(SignalDataPair pair) {
        moveBoth(pair, properties.local().processed());
    }

    public void moveToError(File signalFile) {
        File dataFile = tryResolveDataFile(signalFile);
        moveBoth(new SignalDataPair(signalFile, dataFile), properties.local().error());
    }

    private File tryResolveDataFile(File signalFile) {
        try {
            String referenced = Files.readString(signalFile.toPath()).trim().lines().findFirst().orElse("");
            if (referenced.isEmpty()) {
                return null;
            }
            File data = properties.local().inbox().resolve(referenced).normalize().toFile();
            return data.isFile() ? data : null;
        } catch (IOException e) {
            log.warn("Could not read signal {} to locate paired data file", signalFile.getName(), e);
            return null;
        }
    }

    private void moveBoth(SignalDataPair pair, Path targetRoot) {
        String stamp = LocalDateTime.now().format(TIMESTAMP);
        Path destDir = targetRoot.resolve(stamp);
        try {
            Files.createDirectories(destDir);
            if (pair.dataFile() != null && pair.dataFile().exists()) {
                moveOne(pair.dataFile(), destDir);
            }
            if (pair.signalFile() != null && pair.signalFile().exists()) {
                moveOne(pair.signalFile(), destDir);
            }
            log.info("Moved files to {}", destDir);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to move files to " + destDir, e);
        }
    }

    private void moveOne(File source, Path destDir) throws IOException {
        Path target = destDir.resolve(source.getName());
        Files.move(source.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
    }
}
