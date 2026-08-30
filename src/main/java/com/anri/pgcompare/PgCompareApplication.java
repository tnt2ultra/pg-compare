package com.anri.pgcompare;

import com.anri.pgcompare.cli.CompareCommand;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import picocli.CommandLine;
import picocli.CommandLine.IFactory;

/**
 * Точка входа утилиты. Spring-контейнер поднимается только для того, чтобы собрать бины
 * и передать их picocli-команде: весь сценарий укладывается в один вызов {@link #run(String...)}.
 * Код возврата команды отдаётся наружу через {@link ExitCodeGenerator}, поэтому CI различает
 * «схемы совпали» (0), «есть различия» (1) и «ошибка» (2).
 */
@SpringBootApplication
public class PgCompareApplication implements CommandLineRunner, ExitCodeGenerator {

    /** Команда сравнения — единственный сценарий утилиты. */
    private final CompareCommand compareCommand;

    /** Фабрика picocli: параметры команды разрешаются бинами Spring, а не рефлексируются сами. */
    private final IFactory factory;

    /** Код возврата picocli-команды; Spring читает его после {@link #run(String...)}. */
    private int exitCode;

    /**
     * @param compareCommand команда сравнения схем
     * @param factory        фабрика picocli для внедрения зависимостей в команду
     */
    public PgCompareApplication(CompareCommand compareCommand, IFactory factory) {
        this.compareCommand = compareCommand;
        this.factory = factory;
    }

    /**
     * Прогоняет picocli-команду: разбор опций, сравнение схем и запись отчётов.
     * Подкоманд нет, поэтому аргументы уходят в {@link CompareCommand} как есть.
     *
     * @param args аргументы командной строки
     */
    @Override
    public void run(String... args) {
        exitCode = new CommandLine(compareCommand, factory).execute(args);
    }

    /**
     * @return код возврата, полученный из {@link #run(String...)}
     */
    @Override
    public int getExitCode() {
        return exitCode;
    }

    /**
     * Запускает контейнер и завершает JVM кодом возврата команды:
     * {@link SpringApplication#exit} извлекает {@link #getExitCode()} из раннера.
     *
     * @param args аргументы командной строки
     */
    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(PgCompareApplication.class, args)));
    }
}
