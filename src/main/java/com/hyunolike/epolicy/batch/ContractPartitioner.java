package com.hyunolike.epolicy.batch;

import java.util.HashMap;
import java.util.Map;
import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.item.ExecutionContext;

/**
 * 증권번호 기반 파티셔닝.
 *
 * <p>각 파티션은 {@code MOD(증권번호 끝 4자리, 파티션수) = 파티션번호} 인 계약만 읽는다. 해시 함수를
 * SQL 에서 부르면 H2 와 PostgreSQL 이 서로 다른 이름을 쓰는데, 증권번호 끝자리는 채번 순서라 이미
 * 고르게 퍼져 있어서 나머지 연산만으로 충분하다. DB 이식성을 위해 일부러 단순하게 갔다.
 *
 * <p>파티션 간에 계약이 겹치지 않으므로 같은 문서를 두 워커가 동시에 쓰는 일이 없다. 파티셔닝에서
 * 가장 먼저 깨지는 것이 이 배타성이다.
 */
public class ContractPartitioner implements Partitioner {

    static final String PARTITION_INDEX = "partitionIndex";
    static final String PARTITION_COUNT = "partitionCount";

    @Override
    public Map<String, ExecutionContext> partition(int gridSize) {
        Map<String, ExecutionContext> partitions = new HashMap<>(gridSize);
        for (int index = 0; index < gridSize; index++) {
            ExecutionContext context = new ExecutionContext();
            context.putInt(PARTITION_INDEX, index);
            context.putInt(PARTITION_COUNT, gridSize);
            partitions.put("partition-" + index, context);
        }
        return partitions;
    }
}
