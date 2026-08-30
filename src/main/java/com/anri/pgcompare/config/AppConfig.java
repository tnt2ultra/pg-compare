package com.anri.pgcompare.config;

import com.anri.pgcompare.ddl.DdlGenerator;
import com.anri.pgcompare.diff.SchemaDiffer;
import com.anri.pgcompare.diff.SeverityClassifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the framework-free core. Comparison and DDL generation carry no Spring
 * annotations — they are assembled here so they stay usable as a plain library and are
 * constructed directly by the unit tests.
 */
@Configuration
public class AppConfig {

    @Bean
    SeverityClassifier severityClassifier() {
        return new SeverityClassifier();
    }

    @Bean
    SchemaDiffer schemaDiffer(SeverityClassifier severityClassifier) {
        return new SchemaDiffer(severityClassifier);
    }

    @Bean
    DdlGenerator ddlGenerator() {
        return new DdlGenerator();
    }
}
