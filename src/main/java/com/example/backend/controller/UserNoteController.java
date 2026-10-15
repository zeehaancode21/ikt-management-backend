package com.example.backend.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.backend.entity.ApiResponse;
import com.example.backend.service.UserNoteService;
import com.example.backend.service.UserNoteService.NoteView;
import com.example.backend.service.UserNoteService.TaskItem;
import com.example.backend.service.UserNoteService.TasksView;

/**
 * UserNoteController.java
 * ---------------------------------------------------------------------
 * Personal notepad + task list (header icon next to the calculator).
 *   GET /notes/me          -> the caller's note
 *   PUT /notes/me {content} -> save it (empty content clears it)
 *   GET /notes/tasks        -> the caller's task list
 *   PUT /notes/tasks {tasks:[{id,text,done}]} -> replace it (empty clears it)
 * Always scoped to the logged-in user (JWT) — a user can never read or
 * write anyone else's note, and no id is ever taken from the client.
 * Falls under SecurityConfig's anyRequest().authenticated().
 * ---------------------------------------------------------------------
 */
@RestController
@RequestMapping("/notes")
public class UserNoteController {

    private final UserNoteService service;

    public UserNoteController(UserNoteService service) {
        this.service = service;
    }

    private String currentUsername() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<NoteView>> getMyNote() {
        return ResponseEntity.ok(ApiResponse.success(service.get(currentUsername())));
    }

    @PutMapping("/me")
    public ResponseEntity<ApiResponse<NoteView>> saveMyNote(@RequestBody Map<String, String> body) {
        return ResponseEntity.ok(ApiResponse.success(service.save(currentUsername(), body.get("content"))));
    }

    public record TasksRequest(List<TaskItem> tasks) {
    }

    @GetMapping("/tasks")
    public ResponseEntity<ApiResponse<TasksView>> getMyTasks() {
        return ResponseEntity.ok(ApiResponse.success(service.getTasks(currentUsername())));
    }

    @PutMapping("/tasks")
    public ResponseEntity<ApiResponse<TasksView>> saveMyTasks(@RequestBody TasksRequest body) {
        return ResponseEntity.ok(ApiResponse.success(service.saveTasks(currentUsername(), body.tasks())));
    }
}