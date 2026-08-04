package com.example.backend.controller;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.backend.dto.ProjectYearOption;
import com.example.backend.entity.ApiResponse;
import com.example.backend.entity.ProjectStatus;
import com.example.backend.service.ProjectService;
import com.example.backend.service.ProjectStatusService;

@RestController
@RequestMapping("/project-status")
public class ProjectStatusController {
    
    @Autowired
    private ProjectStatusService projectStatusService;

      @Autowired
    private ProjectService projectService;
    
    // GET all project status
    @GetMapping("/records")
    public ResponseEntity<ApiResponse<List<ProjectStatus>>> getAllProjectStatus() {
        List<ProjectStatus> projectStatusList = projectStatusService.getAllProjectStatus();
        return ResponseEntity.ok(ApiResponse.success(projectStatusList));
    }
    
    // GET single project status by job number
    @GetMapping("/{jobNumber}")
    public ResponseEntity<ApiResponse<ProjectStatus>> getProjectStatusByJobNumber(@PathVariable String jobNumber) {
        ProjectStatus projectStatus = projectStatusService.getProjectStatusByJobNumber(jobNumber);
        return ResponseEntity.ok(ApiResponse.success(projectStatus));
    }
    
    // POST create new project status
    @PostMapping
    public ResponseEntity<ApiResponse<ProjectStatus>> createProjectStatus(@RequestBody ProjectStatus projectStatus) {
        ProjectStatus createdProject = projectStatusService.createProjectStatus(projectStatus);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("Project status created successfully", createdProject));
    }

    @PostMapping("/bulk")
    public ResponseEntity<ApiResponse<List<ProjectStatus>>> createMultipleProjectStatus(
            @RequestBody List<ProjectStatus> projectStatusList) {
        List<ProjectStatus> createdProjects = projectStatusService.createMultipleProjectStatusBulk(projectStatusList);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success(createdProjects.size() + " project status created successfully", createdProjects));
    }
    
    // PUT update project status
    @PutMapping("/{projectStatusId}")
    public ResponseEntity<ApiResponse<ProjectStatus>> updateProjectStatus(
            @PathVariable Long projectStatusId, 
            @RequestBody ProjectStatus projectStatus) {
        ProjectStatus updatedProject = projectStatusService.updateProjectStatus(projectStatusId, projectStatus);
        return ResponseEntity.ok(ApiResponse.success("Project status updated successfully", updatedProject));
    }
    
    // DELETE project status
    @DeleteMapping("/id/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteProjectStatus(@PathVariable Long id) {
        projectStatusService.deleteProjectStatus(id);
        return ResponseEntity.ok(ApiResponse.success("Project status deleted successfully", null));
    }

    // GET project status by client
    @GetMapping
    public ResponseEntity<ApiResponse<List<String>>> getAllClient() {
        List<String> projects = projectStatusService.getAllClients();
        return ResponseEntity.ok(ApiResponse.success(projects));
    }
    
    // GET project status by client
    @GetMapping("/client/{client}")
    public ResponseEntity<ApiResponse<List<String>>> getProjectStatusByClient(@PathVariable String client) {
        List<String> projects = projectStatusService.getProjectsByClients(client);
        return ResponseEntity.ok(ApiResponse.success(projects));
    }
    
    // GET all of a client's projects, grouped by year (year + project name pairs).
    // Used by the Work Report "Projects" dropdown so every project the client has
    // ever had stays browsable in one list, organized by year, instead of being
    // filtered down to a single selected year.
    @GetMapping("/client/{client}/grouped-by-year")
    public ResponseEntity<ApiResponse<List<ProjectYearOption>>> getProjectsByClientGroupedByYear(
            @PathVariable String client) {
        List<ProjectYearOption> projects = projectStatusService.getProjectsByClientGroupedByYear(client);
        return ResponseEntity.ok(ApiResponse.success(projects));
    }

    // GET project status by year
    @GetMapping("/year/{year}")
    public ResponseEntity<ApiResponse<List<ProjectStatus>>> getProjectStatusByYear(@PathVariable String year) {
        List<ProjectStatus> projects = projectStatusService.getProjectStatusByYear(year);
        return ResponseEntity.ok(ApiResponse.success(projects));
    }
    
    // GET project status by client and year
    @GetMapping("/client/{client}/year/{year}")
    public ResponseEntity<ApiResponse<List<ProjectStatus>>> getProjectStatusByClientAndYear(
            @PathVariable String client, @PathVariable String year) {
        List<ProjectStatus> projects = projectStatusService.getProjectStatusByClientAndYear(client, year);
        return ResponseEntity.ok(ApiResponse.success(projects));
    }
}