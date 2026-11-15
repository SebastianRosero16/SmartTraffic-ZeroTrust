package edu.smarttraffic.dashboard.adapter.in.http;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DashboardHomeController {
    @GetMapping("/")
    public String home() {
        return "redirect:/dashboard.html";
    }
}
