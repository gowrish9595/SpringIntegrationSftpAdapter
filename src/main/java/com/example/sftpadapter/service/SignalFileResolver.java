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

@Component
public class SignalFileResolver {

    private static final Logger log = LoggerFactory.getLogger(SignalFileResolver.class);

    private final FileTransferProperties properties;

    public SignalFileResolver(FileTransferProperties properties) {
        this.properties = properties;
    }

    public SignalDataPair resolve(File signalFile) {
        String referencedName = readReferencedDataFileName(signalFile);
        Path dataPath = properties.local().inbox().resolve(referencedName).normalize();

        Path inboxRoot = properties.local().inbox().toAbsolutePath().normalize();
        if (!dataPath.toAbsolutePath().startsWith(inboxRoot)) {
            throw new IllegalStateException("Signal file " + signalFile.getName()
                    + " references a path outside the inbox: " + referencedName);
        }

        File dataFile = dataPath.toFile();
        if (!dataFile.isFile()) {
            throw new IllegalStateException("Signal file " + signalFile.getName()
                    + " references missing data file: " + referencedName);
        }

        log.info("Resolved pair signal={} data={}", signalFile.getName(), dataFile.getName());
        return new SignalDataPair(signalFile, dataFile);
    }

    private String readReferencedDataFileName(File signalFile) {
        try {
            String content = Files.readString(signalFile.toPath()).trim();
            if (content.isEmpty()) {
                throw new IllegalStateException("Signal file is empty: " + signalFile.getName());
            }
            return content.lines().findFirst().orElseThrow().trim();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read signal file " + signalFile.getName(), e);
        }
    }
}
