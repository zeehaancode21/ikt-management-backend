package com.example.backend.service;

import com.example.backend.entity.FavoriteClient;
import com.example.backend.repository.FavoriteClientRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class FavoriteClientService {

    @Autowired
    private FavoriteClientRepository favoriteClientRepository;

    public List<String> getAllFavoriteClientNames() {
        return favoriteClientRepository.findAll()
                .stream()
                .map(FavoriteClient::getClientName)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .collect(Collectors.toList());
    }

    @Transactional
    public List<String> addFavorite(String clientName) {
        if (clientName == null || clientName.isBlank()) {
            throw new IllegalArgumentException("Client name is required");
        }
        String trimmed = clientName.trim();
        if (!favoriteClientRepository.existsByClientNameIgnoreCase(trimmed)) {
            favoriteClientRepository.save(new FavoriteClient(trimmed));
        }
        return getAllFavoriteClientNames();
    }

    @Transactional
    public List<String> removeFavorite(String clientName) {
        if (clientName == null || clientName.isBlank()) {
            throw new IllegalArgumentException("Client name is required");
        }
        favoriteClientRepository.deleteByClientNameIgnoreCase(clientName.trim());
        return getAllFavoriteClientNames();
    }
}