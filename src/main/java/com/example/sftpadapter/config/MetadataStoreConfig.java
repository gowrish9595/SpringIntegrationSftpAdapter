package com.example.sftpadapter.config;

import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.jdbc.metadata.JdbcMetadataStore;
import org.springframework.integration.metadata.ConcurrentMetadataStore;

import javax.sql.DataSource;

@Configuration
public class MetadataStoreConfig {

    @Bean
    @DependsOnDatabaseInitialization
    public ConcurrentMetadataStore metadataStore(DataSource dataSource, FileTransferProperties properties) {
        var store = new JdbcMetadataStore(dataSource);
        store.setRegion(properties.metadataStore().region());
        return store;
    }
}
