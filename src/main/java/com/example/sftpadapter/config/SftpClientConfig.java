package com.example.sftpadapter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.integration.file.remote.session.CachingSessionFactory;
import org.springframework.integration.file.remote.session.SessionFactory;
import org.springframework.integration.sftp.session.DefaultSftpSessionFactory;
import org.springframework.integration.sftp.session.SftpRemoteFileTemplate;
import org.springframework.util.StringUtils;

import org.apache.sshd.sftp.client.SftpClient;

@Configuration
public class SftpClientConfig {

    public static final String REMOTE_DIRECTORY_HEADER = "sftpRemoteDirectory";
    public static final String REMOTE_FILENAME_HEADER = "sftpRemoteFilename";

    @Bean
    public SessionFactory<SftpClient.DirEntry> sftpSessionFactory(FileTransferProperties properties) {
        var factory = new DefaultSftpSessionFactory(true);
        factory.setHost(properties.sftp().host());
        factory.setPort(properties.sftp().port());
        factory.setUser(properties.sftp().username());
        factory.setAllowUnknownKeys(properties.sftp().allowUnknownKeys());

        if (StringUtils.hasText(properties.sftp().password())) {
            factory.setPassword(properties.sftp().password());
        }
        if (StringUtils.hasText(properties.sftp().privateKeyLocation())) {
            Resource keyResource = new DefaultResourceLoader().getResource(properties.sftp().privateKeyLocation());
            factory.setPrivateKey(keyResource);
            if (StringUtils.hasText(properties.sftp().privateKeyPassphrase())) {
                factory.setPrivateKeyPassphrase(properties.sftp().privateKeyPassphrase());
            }
        }
        return new CachingSessionFactory<>(factory);
    }

    @Bean
    public SftpRemoteFileTemplate sftpRemoteFileTemplate(SessionFactory<SftpClient.DirEntry> sftpSessionFactory,
                                                         FileTransferProperties properties) {
        SftpRemoteFileTemplate template = new SftpRemoteFileTemplate(sftpSessionFactory);
        template.setAutoCreateDirectory(true);
        template.setUseTemporaryFileName(true);
        template.setTemporaryFileSuffix(properties.remote().temporaryFileSuffix());
        var parser = new SpelExpressionParser();
        template.setRemoteDirectoryExpression(parser.parseExpression("headers['" + REMOTE_DIRECTORY_HEADER + "']"));
        template.setFileNameGenerator(message -> (String) message.getHeaders().get(REMOTE_FILENAME_HEADER));
        return template;
    }
}
