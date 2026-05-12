package com.example.sftpadapter.service;

import com.example.sftpadapter.config.FileTransferProperties;
import com.example.sftpadapter.domain.SignalDataPair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.integration.file.support.FileExistsMode;
import org.springframework.integration.sftp.session.SftpRemoteFileTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.io.File;

import static com.example.sftpadapter.config.SftpClientConfig.REMOTE_DIRECTORY_HEADER;
import static com.example.sftpadapter.config.SftpClientConfig.REMOTE_FILENAME_HEADER;

@Component
public class SftpPairUploader {

    private static final Logger log = LoggerFactory.getLogger(SftpPairUploader.class);

    private final SftpRemoteFileTemplate template;
    private final FileTransferProperties properties;

    public SftpPairUploader(SftpRemoteFileTemplate template, FileTransferProperties properties) {
        this.template = template;
        this.properties = properties;
    }

    public SignalDataPair upload(SignalDataPair pair) {
        uploadOne(pair.dataFile(), properties.remote().dataDirectory());
        uploadOne(pair.signalFile(), properties.remote().signalDirectory());
        log.info("Uploaded pair signal={} data={}", pair.signalFile().getName(), pair.dataFile().getName());
        return pair;
    }

    private void uploadOne(File file, String remoteDirectory) {
        var message = MessageBuilder.withPayload(file)
                .setHeader(REMOTE_DIRECTORY_HEADER, remoteDirectory)
                .setHeader(REMOTE_FILENAME_HEADER, file.getName())
                .build();
        String storedPath = template.send(message, FileExistsMode.REPLACE);
        log.debug("Uploaded {} to {}", file.getName(), storedPath);
    }
}
