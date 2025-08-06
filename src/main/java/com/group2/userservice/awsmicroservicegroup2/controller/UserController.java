package com.group2.userservice.awsmicroservicegroup2.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;


@RestController
@RequestMapping("/user/api/v1")
public class UserController {
    
    @GetMapping("/data")
    public Map<String, Object> getUserData(@AuthenticationPrincipal OAuth2User principal){
        return Collections.singletonMap("name", principal.getAttribute("username"));
    }

}
