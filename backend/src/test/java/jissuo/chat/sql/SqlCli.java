package jissuo.chat.sql;

import java.util.List;
import java.util.Map;

/** 클라이언트 전용 문법을 검증하기 위해 db/ SQL을 컨테이너 안의 DB 클라이언트로 실행한다. */
interface SqlCli {

    List<List<String>> runFile(String repoPath, Map<String, String> variables);

    List<List<String>> runSql(String sql);
}
