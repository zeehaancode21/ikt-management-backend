package com.example.backend.service;

import com.example.backend.repository.FcmTokenRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class FcmTokenCleaner {

    private final FcmTokenRepository fcmTokenRepository;

    public FcmTokenCleaner(FcmTokenRepository fcmTokenRepository) {
        this.fcmTokenRepository = fcmTokenRepository;
    }

    @Transactional
    public void removeInvalidToken(String token) {
        fcmTokenRepository.deleteByToken(token);
       }
}