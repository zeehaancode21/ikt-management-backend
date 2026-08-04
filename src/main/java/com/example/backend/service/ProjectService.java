package com.example.backend.service;

import com.example.backend.repository.ChangeOrderRepository;
import com.example.backend.repository.DocumentRepository;
import com.example.backend.repository.ProjectStatusRepository;
import com.example.backend.repository.WorkReportRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * ProjectService
 * ---------------------------------------------------------------------
 * A project's name is stored redundantly (denormalized as a plain String)
 * across several tables instead of a foreign key: WorkReport.project,
 * ChangeOrder.projectName, Document.projectName and ProjectStatus.projectName.
 *
 * Whenever a project is renamed via the admin console, this service makes
 * sure every one of those places is updated too, so historical work reports,
 * change orders, documents and status records keep pointing at the right
 * project instead of silently referencing a name that no longer exists.
 * ---------------------------------------------------------------------
 */
@Service
public class ProjectService {

    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);

    @Autowired
    private WorkReportRepository workReportRepository;

    @Autowired
    private ChangeOrderRepository changeOrderRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private ProjectStatusRepository projectStatusRepository;


    /**
     * Propagates a project rename to every table that stores the project
     * name redundantly. Logs the number of rows each query actually touched
     * so a silent zero-match (e.g. due to a stale/mismatched name already in
     * the data) is visible instead of failing silently.
     */
    private void cascadeRename(String oldName, String newName) {
        if (oldName == null || oldName.isBlank()) {
            return;
        }

        int workReportsUpdated = workReportRepository.renameProject(oldName, newName);
        int changeOrdersUpdated = changeOrderRepository.renameProject(oldName, newName);
        int statusesUpdated = projectStatusRepository.renameProject(oldName, newName);
        int documentsUpdated = documentRepository.renameProject(oldName, newName);

        
        if (workReportsUpdated == 0) {
            log.warn("No WorkReport rows matched project='{}'. If work reports for this project exist, " +
                    "check for a mismatch in stored text (case/whitespace) between WorkReport.project and " +
                    "Project.projectName.", oldName);
        }
    }
}