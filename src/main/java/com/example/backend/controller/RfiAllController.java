package com.example.backend.controller;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.backend.entity.ApiResponse;
import com.example.backend.entity.Rfi;
import com.example.backend.entity.RfiDiscipline;
import com.example.backend.entity.RfiPriority;
import com.example.backend.entity.RfiTeamType;
import com.example.backend.service.RfiAccessService;
import com.example.backend.service.RfiService;

/**
 * RFIs of ALL projects in one call — used only by the RFI portal's dashboard
 * ("All projects" option). Read-only. Guarded by the same RFI module gate as the
 * per-project RFI routes (see WebConfig: /api/rfis/**).
 */
@RestController
@RequestMapping("/api/rfis")
@CrossOrigin(origins = {"https://ikt-management-frontend.vercel.app", "http://localhost:5173"})
public class RfiAllController {

    @Autowired
    private RfiService rfiService;

    @Autowired
    private RfiAccessService rfiAccessService;

    // GET /api/rfis/all?teamType=INTERNAL&discipline=STRUCTURAL&priority=HIGH
    @GetMapping("/all")
    public ResponseEntity<ApiResponse<List<Rfi>>> getAllRfis(
            @RequestParam(required = false) RfiTeamType teamType,
            @RequestParam(required = false) RfiDiscipline discipline,
            @RequestParam(required = false) String priority) {
        List<Rfi> rfis = rfiService.getAllRfis(teamType, discipline, RfiPriority.parse(priority));
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        rfiAccessService.annotate(rfis, username);
        return ResponseEntity.ok(ApiResponse.success(rfis));
    }
}