package com.example.gsb.latem;

import com.example.gsb.latem.column.ColumnarTable;
import com.example.gsb.latem.exec.ExecutionStrategy;
import com.example.gsb.latem.exec.ParallelQueryRunner;
import com.example.gsb.latem.exec.Query;
import com.example.gsb.latem.exec.QueryExecutor;
import com.example.gsb.latem.exec.QueryResult;

import org.junit.jupiter.api.Test;

import java.util.Random;

class CorrectnessPropertyTest {

    private static final int ITERATIONS = 40;

    @Test
    void allStrategiesMatchNaiveFullReadOnRandomData() {
        for (int seed = 0; seed < ITERATIONS; seed++) {
            Random random = new Random(seed);
            int rows = 1 + random.nextInt(4000);
            ColumnarTable table = TestTables.randomTable(
                    random, rows, 2 + random.nextInt(3), 1 + random.nextInt(2), 1);
            Query query = TestTables.randomQuery(random, table);

            QueryExecutor executor = new QueryExecutor(table);
            QueryResult naive = executor.executeNaive(query);
            QueryResult late = executor.execute(query, ExecutionStrategy.LATE_MATERIALIZATION);
            QueryResult early = executor.execute(query, ExecutionStrategy.EARLY_MATERIALIZATION);
            QueryResult auto = executor.execute(query);
            try (ParallelQueryRunner runner = new ParallelQueryRunner(table, 4)) {
                QueryResult parallel = runner.execute(query);

                TestTables.assertResultsEqual(naive, late);
                TestTables.assertResultsEqual(naive, early);
                TestTables.assertResultsEqual(naive, auto);
                TestTables.assertResultsEqual(naive, parallel);
            }
        }
    }
}
