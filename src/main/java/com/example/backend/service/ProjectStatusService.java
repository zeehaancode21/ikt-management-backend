package com.example.backend.service;
import java.util.List;
import com.example.backend.dto.ClientYearOption;
import com.example.backend.dto.ProjectYearOption;
import com.example.backend.entity.ProjectStatus;

public interface ProjectStatusService {
    List<ProjectStatus> getAllProjectStatus();
    List<String> getAllClients();
    List<ClientYearOption> getAllClientsGroupedByYear();
    List<String> getProjectsByClients(String client);
    List<ProjectYearOption> getProjectsByClientGroupedByYear(String client);
    ProjectStatus getProjectStatusByJobNumber(String jobNumber);
    ProjectStatus createProjectStatus(ProjectStatus projectStatus);
    List<ProjectStatus> createMultipleProjectStatusBulk(List<ProjectStatus> projectStatusList);
    ProjectStatus updateProjectStatus(Long projectStatusId, ProjectStatus projectStatus);
    void deleteProjectStatus(Long id);
    List<ProjectStatus> getProjectStatusByClient(String client);
    List<ProjectStatus> getProjectStatusByYear(String year);
    List<ProjectStatus> getProjectStatusByClientAndYear(String client, String year);
}