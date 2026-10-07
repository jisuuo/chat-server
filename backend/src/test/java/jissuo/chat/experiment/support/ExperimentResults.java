package jissuo.chat.experiment.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class ExperimentResults {
    private static final Path DIR = Path.of("build", "experiment-results");
    private ExperimentResults() {}
    public static synchronized void record(String experiment, String header, String row) {
        try {
            Files.createDirectories(DIR);
            Path file = DIR.resolve(experiment + ".csv");
            if (Files.notExists(file)) Files.writeString(file, header + "\n");
            Files.writeString(file, row + "\n", StandardOpenOption.APPEND);
            System.out.println("[experiment] " + experiment + " " + row);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
