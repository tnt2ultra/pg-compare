package com.anri.pgcompare;

import com.anri.pgcompare.cli.CompareCommand;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import picocli.CommandLine;
import picocli.CommandLine.IFactory;

@SpringBootApplication
public class PgCompareApplication implements CommandLineRunner, ExitCodeGenerator {

    private final CompareCommand compareCommand;
    private final IFactory factory;
    private int exitCode;

    public PgCompareApplication(CompareCommand compareCommand, IFactory factory) {
        this.compareCommand = compareCommand;
        this.factory = factory;
    }

    @Override
    public void run(String... args) {
        exitCode = new CommandLine(compareCommand, factory).execute(args);
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(PgCompareApplication.class, args)));
    }
}
