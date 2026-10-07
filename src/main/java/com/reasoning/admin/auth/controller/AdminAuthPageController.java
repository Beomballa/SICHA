package com.reasoning.admin.auth.controller;

import com.reasoning.admin.auth.service.LoginSessionService;
import com.reasoning.admin.auth.session.AdminSessionAdapter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.ModelAndView;

import java.util.List;
import java.util.Map;

@Controller
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AdminAuthPageController {
    private final AdminSessionAdapter sessions;
    private final LoginSessionService login;

    public AdminAuthPageController(AdminSessionAdapter sessions, LoginSessionService login) {
        this.sessions = sessions;
        this.login = login;
    }

    /**
     * Render a nonsecret public scaffold or check the current live session and MANAGE permission
     * before protected HTML.
     */
    @GetMapping({
        "/admin/login",
        "/admin/login/mfa",
        "/admin/enroll",
        "/admin/recovery/password",
        "/admin/recovery/mfa",
        "/admin",
        "/admin/preview/player-home",
        "/admin/auth/manage",
        "/admin/accounts"
    })
    public ModelAndView page(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        String path = request.getRequestURI();
        List<String> permissions = List.of();
        boolean protectedPage =
                path.equals("/admin")
                        || path.equals("/admin/preview/player-home")
                        || path.equals("/admin/auth/manage")
                        || path.equals("/admin/accounts");
        if (protectedPage) {
            var session = sessions.current(request);
            if (session.isEmpty()) return loginRedirect(response);
            try {
                permissions = login.me(session.get().id(), session.get().principal()).permissions();
                if ((path.equals("/admin/auth/manage") || path.equals("/admin/accounts"))
                        && !permissions.contains("MANAGE")) {
                    ModelAndView denied = view("FORBIDDEN");
                    denied.setStatus(HttpStatus.FORBIDDEN);
                    return denied;
                }
            } catch (com.reasoning.common.auth.service.AuthException ex) {
                if (ex.status() == 401) return loginRedirect(response);
                throw ex;
            }
        }
        ModelAndView result =
                view(
                        switch (path) {
                            case "/admin/login" -> "AUTH_LOGIN";
                            case "/admin/login/mfa" -> "AUTH_MFA";
                            case "/admin/enroll" -> "AUTH_ENROLL";
                            case "/admin/recovery/password" -> "AUTH_PASSWORD_RECOVERY";
                            case "/admin/recovery/mfa" -> "AUTH_MFA_RECOVERY";
                            case "/admin" -> "ADMIN_HOME";
                            case "/admin/preview/player-home" -> "PLAYER_HOME_PREVIEW";
                            case "/admin/auth/manage" -> "ADMIN_AUTH_MANAGE";
                            case "/admin/accounts" -> "ADMIN_ACCOUNTS";
                            default -> throw new IllegalStateException("Unknown auth page");
                        });
        result.addObject("canManage", permissions.contains("MANAGE"));
        if (path.equals("/admin")) {
            Map<String, String> categories =
                    Map.of(
                            "dashboard", "대시보드",
                            "members", "회원 관리",
                            "statistics", "통계 관리",
                            "security", "보안 관리",
                            "payments", "유료 결제 관리");
            String category = request.getParameter("tab");
            if (category == null) category = "dashboard";
            if (!categories.containsKey(category)) {
                ModelAndView invalid = new ModelAndView("admin/navigation-error");
                invalid.setStatus(HttpStatus.BAD_REQUEST);
                return invalid;
            }
            result.addObject("category", category);
            result.addObject("categoryTitle", categories.get(category));
        }
        return result;
    }

    /**
     * Require a current MANAGE session before rendering a specific account's nonsecret scaffold.
     */
    @GetMapping("/admin/accounts/{accountKey}")
    public ModelAndView accountDetail(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        var session = sessions.current(request);
        if (session.isEmpty()) return loginRedirect(response);
        try {
            if (!login.me(session.get().id(), session.get().principal())
                    .permissions()
                    .contains("MANAGE")) {
                ModelAndView denied = view("FORBIDDEN");
                denied.setStatus(HttpStatus.FORBIDDEN);
                return denied;
            }
        } catch (com.reasoning.common.auth.service.AuthException ex) {
            if (ex.status() == 401) return loginRedirect(response);
            throw ex;
        }
        return view("ADMIN_ACCOUNT_DETAIL");
    }

    private ModelAndView view(String screen) {
        ModelAndView view =
                new ModelAndView(
                        switch (screen) {
                            case "ADMIN_HOME" -> "admin/dashboard";
                            case "PLAYER_HOME_PREVIEW" -> "admin/player-home";
                            default -> "admin/auth";
                        });
        view.addObject("screen", screen);
        view.addObject(
                "canManage",
                screen.equals("ADMIN_AUTH_MANAGE")
                        || screen.equals("ADMIN_ACCOUNTS")
                        || screen.equals("ADMIN_ACCOUNT_DETAIL"));
        return view;
    }

    private ModelAndView loginRedirect(HttpServletResponse response) {
        response.setStatus(HttpStatus.SEE_OTHER.value());
        response.setHeader("Location", "/admin/login");
        return null;
    }
}
