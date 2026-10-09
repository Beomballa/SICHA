package com.reasoning.admin.story.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.reasoning.admin.auth.session.AdminSessionAdapter.CurrentSession;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.story.service.PlaytestInvitationService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 현재 관리자 세션과 사건 REVIEW 권한으로 초대 관리 경계를 연결한다. */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/admin/api/stories/{storyCode}/versions/{versionNo}/playtests")
public final class PlaytestAdminController {
    private final StoryHttpSupport http;
    private final PlaytestInvitationService invitations;

    public PlaytestAdminController(StoryHttpSupport http, PlaytestInvitationService invitations) {
        this.http = http;
        this.invitations = invitations;
    }

    /** 현재 REVIEW 권한으로 배타 커서의 원문 없는 초대 요약을 읽는다. */
    @GetMapping
    public ResponseEntity<?> list(
            @PathVariable String storyCode,
            @PathVariable int versionNo,
            HttpServletRequest request) {
        CurrentSession actor = http.currentSession(request);
        String cursor = request.getParameter("cursor");
        String rawSize = request.getParameter("size");
        if (request.getQueryString() != null && request.getQueryString().isEmpty()
                || request.getParameterMap().keySet().stream()
                        .anyMatch(k -> !Set.of("cursor", "size").contains(k))
                || request.getParameterMap().values().stream().anyMatch(v -> v.length != 1))
            throw AuthException.badRequest("INVALID_REQUEST");
        Long before = cursor == null ? null : positive(cursor);
        long requestedSize = rawSize == null ? 20 : positive(rawSize);
        if (requestedSize > 100) throw AuthException.badRequest("INVALID_REQUEST");
        int size = (int) requestedSize;
        return StoryHttpSupport.ok(
                invitations.getInvitationList(
                        actor.id(),
                        actor.principal(),
                        storyCode,
                        versionNo,
                        before,
                        size,
                        StoryHttpSupport.requestId(request)));
    }

    /** 완전한 의도 본문으로 실제 FUNCTIONAL 초대를 생성한다. */
    @PostMapping
    public ResponseEntity<?> create(
            @PathVariable String storyCode,
            @PathVariable int versionNo,
            HttpServletRequest request) {
        CurrentSession actor = http.currentSession(request);
        JsonNode body =
                http.body(
                        request,
                        8192,
                        Set.of(
                                "expectedRev",
                                "snapshotId",
                                "pair",
                                "memberKeys",
                                "mode",
                                "runtimeConfigId",
                                "requestKey"));
        JsonNode pair = body.get("pair");
        JsonNode members = body.get("memberKeys");
        if (!pair.isArray() || pair.size() != 2 || !members.isArray() || members.size() != 2)
            throw AuthException.badRequest("INVALID_REQUEST");
        List<UUID> keys = new ArrayList<>();
        for (JsonNode member : members) {
            if (!member.isTextual()) throw AuthException.badRequest("INVALID_REQUEST");
            keys.add(StoryHttpSupport.uuid(member.textValue(), true));
        }
        if (!pair.get(0).isTextual() || !pair.get(1).isTextual())
            throw AuthException.badRequest("INVALID_REQUEST");
        return StoryHttpSupport.created(
                invitations.createInvitation(
                        actor.id(),
                        actor.principal(),
                        storyCode,
                        versionNo,
                        decimal(body, "expectedRev", false),
                        decimal(body, "snapshotId", true),
                        StoryHttpSupport.text(body, "runtimeConfigId"),
                        StoryHttpSupport.text(body, "mode"),
                        pair.get(0).textValue(),
                        pair.get(1).textValue(),
                        keys,
                        StoryHttpSupport.uuid(body, "requestKey", true),
                        StoryHttpSupport.requestId(request)));
    }

    /** 다른 사건의 키를 404로 숨기는 서비스의 현재 권한·감사 경계를 사용한다. */
    @GetMapping("/{testKey}")
    public ResponseEntity<?> detail(
            @PathVariable String storyCode,
            @PathVariable int versionNo,
            @PathVariable String testKey,
            HttpServletRequest request) {
        CurrentSession actor = http.currentSession(request);
        noQuery(request);
        return StoryHttpSupport.ok(
                invitations.getInvitationDetail(
                        actor.id(),
                        actor.principal(),
                        storyCode,
                        versionNo,
                        StoryHttpSupport.uuid(testKey, true),
                        StoryHttpSupport.requestId(request)));
    }

    /** 현재 REVIEW 권한을 다시 검사해 WAITING 초대만 감사와 함께 회수한다. */
    @PostMapping("/{testKey}/revoke")
    public ResponseEntity<?> revoke(
            @PathVariable String storyCode,
            @PathVariable int versionNo,
            @PathVariable String testKey,
            HttpServletRequest request) {
        CurrentSession actor = http.currentSession(request);
        JsonNode body =
                http.body(
                        request,
                        8192,
                        Set.of("expectedTestRev", "reasonCode", "verificationRef", "requestKey"));
        return StoryHttpSupport.ok(
                invitations.revokeInvitation(
                        actor.id(),
                        actor.principal(),
                        storyCode,
                        versionNo,
                        StoryHttpSupport.uuid(testKey, true),
                        decimal(body, "expectedTestRev", false),
                        StoryHttpSupport.text(body, "reasonCode"),
                        StoryHttpSupport.text(body, "verificationRef"),
                        StoryHttpSupport.uuid(body, "requestKey", true),
                        StoryHttpSupport.requestId(request)));
    }

    private static long decimal(JsonNode body, String field, boolean positive) {
        return positive(StoryHttpSupport.text(body, field), positive);
    }

    private static long positive(String raw) {
        return positive(raw, true);
    }

    private static long positive(String raw, boolean strictly) {
        if (!raw.matches(strictly ? "[1-9][0-9]*" : "0|[1-9][0-9]*"))
            throw AuthException.badRequest("INVALID_REQUEST");
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException failure) {
            throw AuthException.badRequest("INVALID_REQUEST");
        }
    }

    private static void noQuery(HttpServletRequest request) {
        if (request.getQueryString() != null) throw AuthException.badRequest("INVALID_REQUEST");
    }

    /** 기존 사건 오류 봉투로 입력 및 업무 실패를 원문 없이 반환한다. */
    @ExceptionHandler(AuthException.class)
    public ResponseEntity<?> known(AuthException failure, HttpServletRequest request) {
        return new StoryErrors().known(failure, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> unavailable(Exception failure, HttpServletRequest request) {
        return new StoryErrors().unavailable(failure, request);
    }
}
