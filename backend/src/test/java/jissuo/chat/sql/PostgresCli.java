package jissuo.chat.sql;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

final class PostgresCli implements SqlCli {

    private final PostgreSQLContainer container;

    PostgresCli(PostgreSQLContainer container) {
        this.container = container;
    }

    @Override
    public List<List<String>> runFile(String repoPath, Map<String, String> variables) {
        Path source = Path.of("..", repoPath);
        if (!Files.isRegularFile(source)) {
            throw new IllegalStateException("SQL file not found: " + repoPath);
        }
        String target = "/tmp/" + repoPath.replace('/', '_');
        container.copyFileToContainer(MountableFile.forHostPath(source), target);
        String vars = variables.entrySet().stream()
                .map(e -> " -v " + e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining());
        return exec(client() + vars + " -f " + target);
    }

    @Override
    public List<List<String>> runSql(String sql) {
        return exec(client() + " -c \"" + sql + "\"");
    }

    private String client() {
        // ON_ERROR_STOP은 SQL 오류가 발생해도 종료 코드 0을 돌려주는 psql 기본 동작을 막는다.
        return "psql -X -q -A -t -F '|' -v ON_ERROR_STOP=1 -U " + container.getUsername()
                + " -d " + container.getDatabaseName();
    }

    private List<List<String>> exec(String command) {
        try {
            var result = container.execInContainer("sh", "-c", command);
            if (result.getExitCode() != 0) {
                throw new IllegalStateException(result.getStderr());
            }
            return result.getStdout().lines()
                    .filter(line -> !line.isBlank())
                    .map(line -> Arrays.asList(line.split("\\|", -1)))
                    .toList();
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }
}
