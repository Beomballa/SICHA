package com.reasoning.common.auth.service;

import java.util.UUID;

/** 공통 업무가 관리자 웹 세션 구현을 참조하지 않고 현재 행위자의 식별 세대만 사용한다. */
public interface AdminActor {
    long accountId();

    UUID accountKey();

    UUID sessionKey();

    long authRev();
}
