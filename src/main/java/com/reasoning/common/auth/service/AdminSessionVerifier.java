package com.reasoning.common.auth.service;

/** 공통 사건 업무가 웹 세션 어댑터의 저장 확정을 확인할 때 사용하는 경계다. */
public interface AdminSessionVerifier {
    /**
     * 현재 저장된 프레임워크 세션의 비밀 없는 식별 정보가 요청 행위자와 같은지 확인한다.
     *
     * @param sid 서버에 제시된 현재 세션 ID; null/빈 값은 false
     * @param actor 비교할 현재 관리자 식별·세대; null은 false
     * @return 저장된 현재 주체와 완전히 같은 경우에만 true
     */
    boolean matchesStored(String sid, AdminActor actor);
}
