package com.example.backend.controller;

import com.example.backend.entity.Project;
import com.example.backend.repository.ProjectRepository;
import com.example.backend.service.ProjectService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/projects")
public class ProjectController {

    private final ProjectRepository repo;
    private final ProjectService projectService;

    public ProjectController(ProjectRepository repo, ProjectService projectService) {
        this.repo = repo;
        this.projectService = projectService;
    }

    @GetMapping
    public List<Project> getAll() {
        return repo.findAll();
    }

    @GetMapping("/clients")
    public List<String> getAllClients() {
        return repo.findByClient();
    }

    @GetMapping("/names-by-client")
    public List<String> getProjectNamesByClient(@RequestParam String client) {
        return repo.findProjectNamesByClient(client);
    }

    @PostMapping
    public Project save(@RequestBody Project p) {
        return repo.save(p);
    }

    @PostMapping("/all")
    public Iterable saveAll(@RequestBody Iterable<Project> p) {
        return repo.saveAll(p);
    }

    // PUT /projects/{id} — update project fields. If projectName (or client)
    // changes, ProjectService cascades the rename to every work report,
    // change order, document and project-status record that references it.
    @PutMapping("/{id}")
    public Project update(@PathVariable Long id, @RequestBody Project updated) {
        return projectService.update(id, updated);
    }

    // DELETE /projects/{id}
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        if (!repo.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found");
        }
        repo.deleteById(id);
    }
}