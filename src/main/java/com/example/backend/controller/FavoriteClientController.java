package com.example.backend.controller;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.backend.entity.ApiResponse;
import com.example.backend.service.FavoriteClientService;

/**
 * FavoriteClientController.java
 * ---------------------------------------------------------------------
 * Backs the "favorite clients" chip strip + manage popup on the Hours
 * dashboard (WorkHoursDashboard.tsx). Every response returns the full,
 * current list of pinned client names so the frontend can just replace
 * its local state with response.data after any mutation.
 * ---------------------------------------------------------------------
 */
@RestController
@RequestMapping("/favorite-clients")
public class FavoriteClientController {

    @Autowired
    private FavoriteClientService favoriteClientService;

    // GET the current list of favorited client names
    @GetMapping
    public ResponseEntity<ApiResponse<List<String>>> getFavorites() {
        return ResponseEntity.ok(ApiResponse.success(favoriteClientService.getAllFavoriteClientNames()));
    }

    // POST { "client": "Acme Steel" } -> pin a client, returns updated list
    @PostMapping
    public ResponseEntity<ApiResponse<List<String>>> addFavorite(@RequestBody Map<String, String> body) {
        List<String> updated = favoriteClientService.addFavorite(body.get("client"));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Client pinned to favorites", updated));
    }

    // DELETE /favorite-clients/{client} -> unpin, returns updated list
    @DeleteMapping("/{client}")
    public ResponseEntity<ApiResponse<List<String>>> removeFavorite(@PathVariable String client) {
        List<String> updated = favoriteClientService.removeFavorite(client);
        return ResponseEntity.ok(ApiResponse.success("Client removed from favorites", updated));
    }
}