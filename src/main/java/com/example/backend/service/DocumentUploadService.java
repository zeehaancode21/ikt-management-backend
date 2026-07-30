package com.example.backend.service;

import com.example.backend.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentUploadService {

    @Autowired
    private DocumentRepository repo;

    /**
     * Reads the temp file from disk and writes it as the LONGBLOB.
     * Runs on a background thread — the HTTP response is already sent before
     * this method is called.
     * Always deletes the temp file when done (success or failure).
     */
    @Async("uploadExecutor")
    @Transactional
    public void persistFromTempFile(Long documentId, Path tempFile) {
        try {
            byte[] fileData = Files.readAllBytes(tempFile);
            repo.findById(documentId).ifPresent(doc -> {
                doc.setFileData(fileData);
                doc.setUploadStatus("READY");
                repo.save(doc);
             });
        } catch (IOException e) {
            log.error("Failed to persist document {}", documentId, e);
            repo.findById(documentId).ifPresent(doc -> {
                doc.setUploadStatus("FAILED");
                repo.save(doc);
            });
        } finally {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException e) {}
        }
    }
}