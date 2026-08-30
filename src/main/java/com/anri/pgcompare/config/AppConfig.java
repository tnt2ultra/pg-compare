package com.anri.pgcompare.config;

import com.anri.pgcompare.ddl.DdlGenerator;
import com.anri.pgcompare.diff.SchemaDiffer;
import com.anri.pgcompare.diff.SeverityClassifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Сборка не зависящего от фреймворка ядра. Сравнение и генерация DDL не несут аннотаций
 * Spring — бинами они становятся здесь, чтобы ядро оставалось обычной библиотекой и
 * создавалось unit-тестами напрямую.
 */
@Configuration
public class AppConfig {

    /**
     * Классификатор severity: определяет по виду объекта и типу изменения, насколько опасным
     * является это изменение.
     *
     * @return бин {@link SeverityClassifier}
     */
    @Bean
    SeverityClassifier severityClassifier() {
        return new SeverityClassifier();
    }

    /**
     * Собственно сравнение двух снимков схемы. Классификатор severity передаётся конструктором,
     * чтобы ядро оставалось обычным классом без аннотаций Spring.
     *
     * @param severityClassifier классификатор severity для записей диффа
     * @return бин {@link SchemaDiffer}
     */
    @Bean
    SchemaDiffer schemaDiffer(SeverityClassifier severityClassifier) {
        return new SchemaDiffer(severityClassifier);
    }

    /**
     * Генератор миграционного SQL по результату диффа.
     *
     * @return бин {@link DdlGenerator}
     */
    @Bean
    DdlGenerator ddlGenerator() {
        return new DdlGenerator();
    }
}
