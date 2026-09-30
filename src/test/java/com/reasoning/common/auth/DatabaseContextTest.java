package com.reasoning.common.auth;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInfo;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestContextManager;

/** 클래스 전용 DB보다 Spring 예약 작업·연결 풀이 먼저 종료되도록 수명을 정렬한다. */
public abstract class DatabaseContextTest {

    /**
     * Testcontainers의 afterAll 확장 콜백 전에 Context를 캐시에서 제거하며 종료한다.
     *
     * @param info 현재 테스트 클래스가 포함된 JUnit 정보이며 null이 아니다
     */
    @AfterAll
    static void closeDatabaseClients(TestInfo info) {
        new TestContextManager(info.getTestClass().orElseThrow())
                .getTestContext()
                .markApplicationContextDirty(DirtiesContext.HierarchyMode.EXHAUSTIVE);
    }
}
