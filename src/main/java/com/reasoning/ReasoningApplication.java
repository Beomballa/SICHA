package com.reasoning;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class ReasoningApplication {
    public static void main(String[] args) {
        boolean offline =
                java.util.Arrays.stream(args)
                        .anyMatch(
                                arg ->
                                        arg.equals("--h0-command=bootstrap-create")
                                                || arg.equals("--h0-command=bootstrap-reissue")
                                                || arg.equals("--h0-command=recovery-issue")
                                                || arg.equals("--h0-command=admin-block")
                                                || arg.equals(
                                                        "--h0-command=admin-reactivation-preview")
                                                || arg.equals("--h0-command=admin-reactivate"));
        new SpringApplicationBuilder(ReasoningApplication.class)
                .web(offline ? WebApplicationType.NONE : WebApplicationType.SERVLET)
                .run(args);
    }
}
