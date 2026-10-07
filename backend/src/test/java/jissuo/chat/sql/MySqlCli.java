package jissuo.chat.sql;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.MountableFile;

final class MySqlCli implements SqlCli {

    private final MySQLContainer container;

    MySqlCli(MySQLContainer container) {
        this.container = container;
    }

    @Override
    public List<List<String>> runFile(String repoPath, Map<String, String> variables) {
        Path source = Path.of("..", repoPath);
        if (!Files.isRegularFile(source)) {
            throw new IllegalStateException("SQL file not found: " + repoPath);
        }
        String target = "/tmp/" + repoPath.replace('/', '_');
        // Gradle 테스트는 backend/에서 실행되므로 저장소 루트의 db/를 한 단계 위에서 찾는다.
        container.copyFileToContainer(MountableFile.forHostPath(source), target);
        String init = variables.entrySet().stream()
                .map(e -> "SET @" + e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(";"));
        return exec(client() + (init.isEmpty() ? "" : " --init-command='" + init + "'") + " < " + target);
    }

    @Override
    public List<List<String>> runSql(String sql) {
        return exec(client() + " -e \"" + sql + "\"");
    }

    private String client() {
        return "mysql --default-character-set=utf8mb4 -N -B -u" + container.getUsername()
                + " -p" + container.getPassword() + " " + container.getDatabaseName();
    }

    private List<List<String>> exec(String command) {
        try {
            var result = container.execInContainer("sh", "-c", command);
            if (result.getExitCode() != 0) {
                throw new IllegalStateException(result.getStderr());
            }
            return result.getStdout().lines()
                    .filter(line -> !line.isBlank())
                    .map(line -> Arrays.asList(line.split("\t", -1)))
                    .toList();
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }
}
