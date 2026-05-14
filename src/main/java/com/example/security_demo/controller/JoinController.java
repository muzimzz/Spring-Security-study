package com.example.security_demo.controller;

import com.example.security_demo.dto.UserJoinRequest;
import com.example.security_demo.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

@Controller
@RequiredArgsConstructor
public class JoinController {

    private final UserService userService;

    @GetMapping("/join")
    public String joinPage() {
        return "join";
    }

    @PostMapping("/join")
    public String join(@ModelAttribute UserJoinRequest request) {
        userService.join(request);
        return "redirect:/login";
    }
}
