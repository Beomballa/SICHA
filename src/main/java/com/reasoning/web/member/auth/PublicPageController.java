package com.reasoning.web.member.auth;

import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.member.auth.MemberAuthService;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** 공개 소개·고지와 회원 계정 문서를 같은 HTTPS 원점에서 제공한다. */
@Controller
@ConditionalOnProperty(name = "app.member-web.enabled", havingValue = "true")
public class PublicPageController {
    private final MemberAuthService auth;

    /** 기존 회원 고지 권위를 사용하며 Flutter 자산 설치에 의존하지 않는다. */
    public PublicPageController(MemberAuthService auth) {
        this.auth = auth;
    }

    /** 소개 문서의 계정 링크는 실제 제공하는 웹 계정 문서로 연결한다. */
    @GetMapping("/")
    public String home() {
        return "player/home";
    }

    /** 인증 자격·flow 비밀을 HTML에 넣지 않고 계정 문서만 제공한다. */
    @GetMapping("/account")
    public String account() {
        return "player/account";
    }

    /** 사건 자료·자격을 서버 HTML에 포함하지 않고 회원용 단독 조사 문서만 제공한다. */
    @GetMapping("/play")
    public String play() {
        return "player/play";
    }

    /** 현재 환경의 검증된 회원 고지만 표시하며 준비되지 않은 법적 내용을 대신 만들지 않는다. */
    @GetMapping("/privacy")
    public String privacy(Model model, HttpServletResponse response) {
        try {
            model.addAttribute("notice", auth.getNotice(java.util.UUID.randomUUID()));
        } catch (AuthException failure) {
            response.setStatus(failure.status());
            model.addAttribute("unavailable", true);
        }
        return "player/privacy";
    }
}
