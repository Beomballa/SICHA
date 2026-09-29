package com.reasoning.admin.story.controller;

import com.reasoning.admin.auth.session.AdminSessionAdapter;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.admin.auth.service.LoginSessionService;
import com.reasoning.common.story.service.StoryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.server.ResponseStatusException;

/** 사건별 현재 조회 권한을 확인한 뒤 원고가 없는 화면 틀만 제공한다. */
@Controller
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class StoryPageController {
    private final AdminSessionAdapter sessions;
    private final LoginSessionService login;
    private final StoryService stories;

    public StoryPageController(AdminSessionAdapter sessions, LoginSessionService login, StoryService stories) {
        this.sessions = sessions;
        this.login = login;
        this.stories = stories;
    }

    /** 현재 관리자 세션을 확인한 뒤 사건 목록 화면 틀을 반환한다.
     * @param request 관리자 세션을 확인할 현재 HTTP 요청
     * @param response 캐시 금지 또는 로그인 리다이렉트를 기록할 응답
     * @return 사건 데이터가 없는 목록 화면이며 리다이렉트 시 {@code null}
     */
    @GetMapping("/admin/stories")
    public ModelAndView list(HttpServletRequest request, HttpServletResponse response) {
        return page(request, response, "admin/stories");
    }

    /** 현재 버전의 조회 자격을 서버에서 확인한 뒤 원고 없는 편집 틀을 반환한다.
     * @param storyCode 현재 조회 권한을 확인할 정확한 사건 코드
     * @param versionNo 양의 버전 번호
     * @param request 관리자 세션을 확인할 현재 HTTP 요청
     * @param response 캐시 금지 또는 로그인 리다이렉트를 기록할 응답
     * @return 사건 데이터가 없는 편집 화면이며 리다이렉트 시 {@code null}
     */
    @GetMapping("/admin/stories/{storyCode}/versions/{versionNo}")
    public ModelAndView editor(@PathVariable String storyCode, @PathVariable int versionNo,
            HttpServletRequest request, HttpServletResponse response) {
        ModelAndView view = page(request, response, "admin/story-editor");
        if (view == null) return null;

        var session = sessions.current(request).orElseThrow();
        try {
            stories.checkStoryAccess(session.id(), session.principal(), storyCode, versionNo);
            return view;
        } catch (AuthException error) {
            if (error.status() == 401) return loginRedirect(response);
            throw new ResponseStatusException(HttpStatus.valueOf(error.status()));
        }
    }

    /** 관리자 화면을 캐시하지 않고 REST 권한 확인 전에 사건 데이터를 넣지 않는다.
     * @param request 세션 자격을 담은 현재 요청
     * @param response 캐시 정책이나 로그인 리다이렉트를 기록할 응답
     * @param template 고정된 관리자 사건 템플릿명 중 하나
     * @return 템플릿 화면이며 로그인 리다이렉트가 필요하면 {@code null}
     * @throws AuthException 세션 만료 이외의 현재 세션 검증 실패 시
     */
    private ModelAndView page(HttpServletRequest request, HttpServletResponse response, String template) {
        response.setHeader("Cache-Control", "no-store");
        var session = sessions.current(request);
        if (session.isEmpty()) return loginRedirect(response);
        try {
            login.me(session.get().id(), session.get().principal());
        } catch (AuthException ex) {
            if (ex.status() == 401) return loginRedirect(response);
            throw ex;
        }
        return new ModelAndView(template);
    }

    private ModelAndView loginRedirect(HttpServletResponse response) {
        response.setStatus(HttpStatus.SEE_OTHER.value());
        response.setHeader("Location", "/admin/login");
        return null;
    }
}
