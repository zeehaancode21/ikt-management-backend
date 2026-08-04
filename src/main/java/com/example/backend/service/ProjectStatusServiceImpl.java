package com.example.backend.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.backend.Exception.ResourceNotFoundException;
import com.example.backend.dto.ProjectYearOption;
import com.example.backend.entity.ProjectStatus;
import com.example.backend.repository.ProjectStatusRepository;

@Service
@Transactional
public class ProjectStatusServiceImpl implements ProjectStatusService {

    private static final Logger log = LoggerFactory.getLogger(ProjectStatusServiceImpl.class);

    @Autowired
    private ProjectStatusRepository projectStatusRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Override
    public List<String> getAllClients(){
      return projectStatusRepository.getAllClients();
    }

    @Override
    public List<String> getProjectsByClients(String client){
        return projectStatusRepository.getProjectsByClients(client);
    }

    @Override
    public List<ProjectYearOption> getProjectsByClientGroupedByYear(String client){
        return projectStatusRepository.getProjectsByClientGroupedByYear(client);
    }
    
    @Override
    public List<ProjectStatus> getAllProjectStatus() {
        return projectStatusRepository.findAll();
    }
    
    @Override
    public ProjectStatus getProjectStatusByJobNumber(String jobNumber) {
        return projectStatusRepository.findByJobNumber(jobNumber)
            .orElseThrow(() -> new ResourceNotFoundException("Project status not found with job number: " + jobNumber));
    }
    
    @Override
    public ProjectStatus createProjectStatus(ProjectStatus projectStatus) {
       if (projectStatusRepository.existsByJobNumber(projectStatus.getJobNumber()) && !projectStatus.getJobNumber().isEmpty()) {
            throw new RuntimeException("Project status with job number " + projectStatus.getJobNumber() + " already exists");
        }
        normalizeTextFields(projectStatus);
        projectStatus.setCreatedAt(LocalDateTime.now());
        projectStatus.setUpdatedAt(LocalDateTime.now());
        return projectStatusRepository.save(projectStatus);
    }

    /**
     * Trims leading/trailing whitespace (and collapses internal double-spaces)
     * on the free-text fields that are used to identify a project — client and
     * projectName in particular. Without this, saving "Project A" and
     * "Project A " (trailing space) are treated as two different projects
     * everywhere they're compared with plain string equality (e.g. the
     * DISTINCT project-name dropdown query), even though they're clearly the
     * same project to a human.
     */
    private void normalizeTextFields(ProjectStatus projectStatus) {
        if (projectStatus.getClient() != null)
            projectStatus.setClient(collapseSpaces(projectStatus.getClient()));
        if (projectStatus.getProjectName() != null)
            projectStatus.setProjectName(collapseSpaces(projectStatus.getProjectName()));
        if (projectStatus.getProjectManager() != null)
            projectStatus.setProjectManager(collapseSpaces(projectStatus.getProjectManager()));
        if (projectStatus.getTeam() != null)
            projectStatus.setTeam(collapseSpaces(projectStatus.getTeam()));
    }

    /** Trims the string and collapses any run of internal whitespace down to a single space. */
    private String collapseSpaces(String value) {
        return value.trim().replaceAll("\\s+", " ");
    }

    // CREATE multiple project status (bulk insert)
    public List<ProjectStatus> createMultipleProjectStatus(List<ProjectStatus> projectStatusList) {
        List<ProjectStatus> savedProjects = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        
        for (int i = 0; i < projectStatusList.size(); i++) {
            ProjectStatus projectStatus = projectStatusList.get(i);
            try {
                if (projectStatusRepository.existsByJobNumber(projectStatus.getJobNumber()) && projectStatus.getJobNumber() != null && !projectStatus.getJobNumber().isEmpty() ) {
                    errors.add("Row " + (i + 1) + ": Job number " + projectStatus.getJobNumber() + " already exists");
                    continue;
                }
                normalizeTextFields(projectStatus);
                projectStatus.setCreatedAt(LocalDateTime.now());
                projectStatus.setUpdatedAt(LocalDateTime.now());
                savedProjects.add(projectStatusRepository.save(projectStatus));
            } catch (Exception e) {
                errors.add("Row " + (i + 1) + ": " + e.getMessage());
            }
        }
        
        if (!errors.isEmpty() && savedProjects.isEmpty()) {
            throw new RuntimeException("Failed to create projects: " + String.join(", ", errors));
        }
        
        return savedProjects;
    }
    
    // CREATE multiple project status (save all at once - faster)
    public List<ProjectStatus> createMultipleProjectStatusBulk(List<ProjectStatus> projectStatusList) {
        java.util.Set<String> seenInThisBatch = new java.util.HashSet<>();

        for (ProjectStatus projectStatus : projectStatusList) {
            String jobNumber = projectStatus.getJobNumber();
            boolean hasJobNumber = jobNumber != null && !jobNumber.isEmpty();

            if (hasJobNumber && projectStatusRepository.existsByJobNumber(jobNumber)) {
                throw new RuntimeException("Project status with job number " + jobNumber + " already exists");
            }
            if (hasJobNumber && !seenInThisBatch.add(jobNumber)) {
                throw new RuntimeException("Duplicate job number " + jobNumber + " within the uploaded batch");
            }

            normalizeTextFields(projectStatus);
            projectStatus.setCreatedAt(LocalDateTime.now());
            projectStatus.setUpdatedAt(LocalDateTime.now());
        }

        return projectStatusRepository.saveAll(projectStatusList);
    }

    /**
     * Updates a project status record. If the project name (or client)
     * changes here, the rename is cascaded to every other table that stores
     * the project name as denormalized plain text: the project master table,
     * work_report, change_orders and documents — so historical records keep
     * pointing at the right project instead of the stale old name.
     */
    @Override
    public ProjectStatus updateProjectStatus(Long projectStatusId, ProjectStatus projectStatusDetails) {
        
        ProjectStatus existingProject = projectStatusRepository.findProjectStatusById(projectStatusId)
        .orElseThrow(() -> new ResourceNotFoundException("Project status not found with id: " + projectStatusId));

        String oldClient = existingProject.getClient();
        String oldProjectName = existingProject.getProjectName();

        // Normalize the incoming payload FIRST so a stray leading/trailing
        // space typed into the name field never turns into a "new" project.
        // Without this, "Project A" and "Project A " compare as different
        // strings everywhere downstream (dropdowns, cascade matching, etc.)
        // even though a human would call them the same project.
        normalizeTextFields(projectStatusDetails);

        log.info(">>> updateProjectStatus() id={} | oldClient='{}' oldProjectName='{}' | incoming client='{}' incoming projectName='{}'",
                projectStatusId, oldClient, oldProjectName,
                projectStatusDetails.getClient(), projectStatusDetails.getProjectName());

        // Update only non-null fields
        if (projectStatusDetails.getClient() != null) 
            existingProject.setClient(projectStatusDetails.getClient());
        if (projectStatusDetails.getProjectName() != null) 
            existingProject.setProjectName(projectStatusDetails.getProjectName());
        if (projectStatusDetails.getApprovalStatus() != null) 
            existingProject.setApprovalStatus(projectStatusDetails.getApprovalStatus());
        if (projectStatusDetails.getFabStatus() != null) 
            existingProject.setFabStatus(projectStatusDetails.getFabStatus());
        if (projectStatusDetails.getRemarks() != null) 
            existingProject.setRemarks(projectStatusDetails.getRemarks());
        if (projectStatusDetails.getProjectManager() != null) 
            existingProject.setProjectManager(projectStatusDetails.getProjectManager());
        if (projectStatusDetails.getJobNumber() != null) 
            existingProject.setJobNumber(projectStatusDetails.getJobNumber());
        if (projectStatusDetails.getTeam() != null) 
            existingProject.setTeam(projectStatusDetails.getTeam());
        if (projectStatusDetails.getIfcDate() != null) 
            existingProject.setIfcDate(projectStatusDetails.getIfcDate());
        if (projectStatusDetails.getIfaDate() != null) 
            existingProject.setIfaDate(projectStatusDetails.getIfaDate());
        if (projectStatusDetails.getYear() != null)
            existingProject.setYear(projectStatusDetails.getYear());
        
        existingProject.setUpdatedAt(LocalDateTime.now());
        ProjectStatus saved = projectStatusRepository.save(existingProject);

        String newClient = saved.getClient();
        String newProjectName = saved.getProjectName();

        log.info(">>> Saved project status. newClient='{}' newProjectName='{}'", newClient, newProjectName);

        cascadeRename(oldClient, oldProjectName, newClient, newProjectName);

        return saved;
    }
    
    @Override
    public void deleteProjectStatus(Long id) {
        ProjectStatus projectStatus = projectStatusRepository.findProjectStatusById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Project status not found with id: " + id));
        projectStatusRepository.delete(projectStatus);
    }
    
    @Override
    public List<ProjectStatus> getProjectStatusByClient(String client) {
        return projectStatusRepository.findByClient(client);
    }
    
    @Override
    public List<ProjectStatus> getProjectStatusByYear(String year) {
        return projectStatusRepository.findByYear(year);
    }
    
    @Override
    public List<ProjectStatus> getProjectStatusByClientAndYear(String client, String year) {
        return projectStatusRepository.findByClientAndYear(client, year);
    }

    /**
     * Propagates a project-status rename/re-client to every table that
     * stores the project name (and client) as denormalized plain text.
     * Matching is done case/whitespace-insensitively via TRIM(LOWER(...))
     * so minor formatting drift between tables doesn't cause a silent
     * zero-row match.
     */
    private void cascadeRename(String oldClient, String oldProjectName, String newClient, String newProjectName) {
        boolean projectNameChanged = oldProjectName != null && newProjectName != null
                && !oldProjectName.trim().equalsIgnoreCase(newProjectName.trim());
        boolean clientChanged = oldClient != null && newClient != null
                && !oldClient.trim().equalsIgnoreCase(newClient.trim());

        log.info(">>> cascadeRename check: projectNameChanged={}, clientChanged={}", projectNameChanged, clientChanged);

        if (!projectNameChanged && !clientChanged) {
            log.info(">>> Skipping cascade — neither project name nor client changed.");
            return;
        }

        if (oldProjectName == null || oldProjectName.isBlank()) {
            log.warn(">>> Skipping cascade — oldProjectName is null/blank, nothing reliable to match on.");
            return;
        }

        String effectiveNewClient = newClient != null ? newClient.trim() : oldClient;
        String effectiveNewProjectName = newProjectName != null ? newProjectName.trim() : oldProjectName;

        // Project master table — has both client and project_name columns.
        // int projectRows = jdbcTemplate.update(
        //         "UPDATE project SET project_name = ?, client = ? " +
        //                 "WHERE TRIM(LOWER(project_name)) = TRIM(LOWER(?)) " +
        //                 "AND (client IS NULL OR TRIM(LOWER(client)) = TRIM(LOWER(?)))",
        //         effectiveNewProjectName, effectiveNewClient, oldProjectName, oldClient
        // );

        // work_report — has both client and project columns.
        int workReportsUpdated = jdbcTemplate.update(
                "UPDATE work_report SET project = ?, client = ? " +
                        "WHERE TRIM(LOWER(project)) = TRIM(LOWER(?)) " +
                        "AND (client IS NULL OR TRIM(LOWER(client)) = TRIM(LOWER(?)))",
                effectiveNewProjectName, effectiveNewClient, oldProjectName, oldClient
        );

        // change_orders — no client column, match on project_name only.
        int changeOrdersUpdated = jdbcTemplate.update(
                "UPDATE change_orders SET project_name = ? WHERE TRIM(LOWER(project_name)) = TRIM(LOWER(?))",
                effectiveNewProjectName, oldProjectName
        );

        // documents — no client column, match on project_name only.
        int documentsUpdated = jdbcTemplate.update(
                "UPDATE documents SET project_name = ? WHERE TRIM(LOWER(project_name)) = TRIM(LOWER(?))",
                effectiveNewProjectName, oldProjectName
        );

        // log.info(">>> Cascade complete for '{}' -> '{}': project={}, work_report={}, change_orders={}, documents={}",
        //         oldProjectName, effectiveNewProjectName, projectRows, workReportsUpdated, changeOrdersUpdated, documentsUpdated);

        if (workReportsUpdated == 0) {
            log.warn(">>> WARNING: 0 rows matched in work_report for project='{}' client='{}'. " +
                    "Either no reports exist for this project/client combination, or the stored text " +
                    "differs beyond case/whitespace.", oldProjectName, oldClient);
        }
    }
}