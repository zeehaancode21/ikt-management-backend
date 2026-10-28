package com.example.backend.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.config.annotation.ContentNegotiationConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.example.backend.repository.UserRepository;
import com.example.backend.security.ProjectsAccessInterceptor;
import com.example.backend.security.RfiAccessInterceptor;
import com.example.backend.security.RfiModuleAccessInterceptor;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Autowired
    private RfiAccessInterceptor rfiAccessInterceptor;

    @Autowired
    private RfiModuleAccessInterceptor rfiModuleAccessInterceptor;

    @Autowired
    private UserRepository userRepository;

    @Override
    public void configureContentNegotiation(ContentNegotiationConfigurer configurer) {
        configurer.defaultContentType(MediaType.APPLICATION_JSON);
    }

    // RFI write routes (status / priority / reply / resubmit / reopen): only people on the RFI's To/CC list get through.
    // (If you add a new RFI action that changes something, add its path here.)
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 1) Module gate (runs first): only the OWNER and users the owner switched on in
        //    Admin Console -> Access Control may use the RFI module at all (read AND write).
        //    /api/rfi-replies/check is intentionally NOT listed: the app calls it in the
        //    background for every logged-in user and it only touches the caller's own RFIs.
        registry.addInterceptor(rfiModuleAccessInterceptor)
                .addPathPatterns(
                        "/api/project-status/*/rfis",
                        "/api/project-status/*/rfis/**",
                        // All-projects RFI list (RFI dashboard "All projects" view).
                        "/api/rfis/**",
                        "/api/rfi-settings/**",
                        // Client share links for RFIs (create / list / revoke). The PUBLIC
                        // read endpoint is /public/share/rfi/** and is intentionally not gated.
                        "/api/rfi-share-links",
                        "/api/rfi-share-links/**");

        // 1b) Projects module gate: only the OWNER and users the owner ticked "Projects" for in
        //     Admin Console -> Access Control may change projects or touch change orders.
        //     (Plain instances, not beans - so no extra memory.)
        //     /project-status/** : GET stays open (Work Report, Work Hours and the RFI form read the
        //                          project list); create / edit / delete are gated.
        registry.addInterceptor(new ProjectsAccessInterceptor(userRepository, true))
                .addPathPatterns(
                        "/project-status",
                        "/project-status/**");
        //     change orders: used only by the Projects page, so every call is gated.
        registry.addInterceptor(new ProjectsAccessInterceptor(userRepository, false))
                .addPathPatterns(
                        "/api/project-status/*/change-orders",
                        "/api/project-status/*/change-orders/**",
                        "/api/change-orders/**");

        // 2) Per-RFI gate: of the people who got in, only To/CC/sender may change a given RFI.
        registry.addInterceptor(rfiAccessInterceptor)
                .addPathPatterns(
                        "/api/project-status/*/rfis/*/status",
                        "/api/project-status/*/rfis/*/priority",
                        "/api/project-status/*/rfis/*/reply",
                        "/api/project-status/*/rfis/*/resubmit",
                        "/api/project-status/*/rfis/*/reopen");
    }
}