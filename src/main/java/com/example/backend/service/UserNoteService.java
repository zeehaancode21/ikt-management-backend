package com.example.backend.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.example.backend.Exception.ResourceNotFoundException;
import com.example.backend.repository.UserRepository;

/**
 * Personal notepad + task list. Both live in the user's own row in the `users`
 * table (note_content / note_updated_at and task_content) — no separate table,
 * no extra primary key, no extra index.
 */
@Service
public class UserNoteService {

    /** Hard cap per user (characters). */
    public static final int MAX_LENGTH = 5000;

    /** What the API returns (unchanged — the frontend needs no changes). */
    public record NoteView(String content, Instant updatedAt, int maxLength) {
    }

    /** Task list limits (kept small on purpose — the whole list is one TEXT cell). */
    public static final int MAX_TASKS = 100;
    public static final int MAX_TASK_LENGTH = 200;
    private static final int MAX_TASK_ID_LENGTH = 24;

    public record TaskItem(String id, String text, boolean done) {
    }

    public record TasksView(List<TaskItem> tasks, int maxTasks, int maxTaskLength) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<TaskItem>> TASK_LIST = new TypeReference<>() {
    };

    private final UserRepository users;

    public UserNoteService(UserRepository users) {
        this.users = users;
    }

    public NoteView get(String username) {
        return users.findNoteByUsername(username)
                .map(n -> new NoteView(
                        n.getContent() == null ? "" : n.getContent(),
                        n.getUpdatedAt() == null ? null : n.getUpdatedAt().toInstant(),
                        MAX_LENGTH))
                .orElseGet(() -> new NoteView("", null, MAX_LENGTH));
    }

    public NoteView save(String username, String content) {
        String value = content == null ? "" : content;
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Note is too long (max " + MAX_LENGTH + " characters)");
        }

        // Empty pad -> NULL the columns so nothing is stored.
        if (value.isBlank()) {
            users.clearNote(username);
            return new NoteView("", null, MAX_LENGTH);
        }

        Instant now = Instant.now();
        if (users.saveNote(username, value, Timestamp.from(now)) == 0) {
            throw new ResourceNotFoundException("User not found");
        }
        return new NoteView(value, now, MAX_LENGTH);
    }

    public TasksView getTasks(String username) {
        List<TaskItem> list = users.findTasksByUsername(username)
                .map(UserNoteService::parseTasks)
                .orElseGet(List::of);
        return new TasksView(list, MAX_TASKS, MAX_TASK_LENGTH);
    }

    public TasksView saveTasks(String username, List<TaskItem> incoming) {
        List<TaskItem> clean = validateTasks(incoming);

        // Empty list -> NULL the column so nothing is stored.
        String json = null;
        if (!clean.isEmpty()) {
            try {
                json = JSON.writeValueAsString(clean);
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("Invalid tasks");
            }
        }
        if (users.saveTasks(username, json) == 0) {
            throw new ResourceNotFoundException("User not found");
        }
        return new TasksView(clean, MAX_TASKS, MAX_TASK_LENGTH);
    }

    private static List<TaskItem> validateTasks(List<TaskItem> incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return List.of();
        }
        if (incoming.size() > MAX_TASKS) {
            throw new IllegalArgumentException("Too many tasks (max " + MAX_TASKS + ")");
        }
        return incoming.stream().map(t -> {
            String text = t == null || t.text() == null ? "" : t.text().trim();
            String id = t == null || t.id() == null ? "" : t.id().trim();
            if (text.isEmpty() || id.isEmpty()) {
                throw new IllegalArgumentException("Invalid task");
            }
            if (text.length() > MAX_TASK_LENGTH) {
                throw new IllegalArgumentException("Task is too long (max " + MAX_TASK_LENGTH + " characters)");
            }
            if (id.length() > MAX_TASK_ID_LENGTH) {
                throw new IllegalArgumentException("Invalid task id");
            }
            return new TaskItem(id, text, t.done());
        }).toList();
    }

    private static List<TaskItem> parseTasks(String json) {
        try {
            return JSON.readValue(json, TASK_LIST);
        } catch (JsonProcessingException e) {
            return List.of(); // corrupt cell -> behave as empty rather than 500
        }
    }
}