// FileStorageService.java
package com.example.backend.service;

import com.example.backend.entity.Attachment;
import com.example.backend.repository.AttachmentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class FileStorageService {
    
    @Autowired
    private AttachmentRepository attachmentRepository;

    // Anchored to the user's home directory so it resolves to the same
    // physical folder no matter how the app is launched (IDE, jar, etc.)
    // instead of depending on the process's working directory.
    private Path resolveUploadPath() throws IOException {
        Path uploadPath = Paths.get(System.getProperty("user.home"), "app-data", "uploads");
        if (!Files.exists(uploadPath)) {
            Files.createDirectories(uploadPath);
        }
        return uploadPath;
    }

    public List<Attachment> storeFiles(List<MultipartFile> files, String uploadedBy) throws IOException {
        List<Attachment> attachments = new ArrayList<>();

        Path uploadPath = resolveUploadPath();
        
        for (MultipartFile file : files) {
            String originalFilename = file.getOriginalFilename();
            String fileExtension = "";
            if (originalFilename != null && originalFilename.contains(".")) {
                fileExtension = originalFilename.substring(originalFilename.lastIndexOf("."));
            }
            // Previously: a filename like "x.png/../../../etc/cron.d/evil" made
            // fileExtension itself contain "/../" segments, which Path#resolve()
            // would then happily walk outside uploadDir. Strip anything that
            // isn't a short, plain extension.
            fileExtension = fileExtension.replaceAll("[^a-zA-Z0-9.]", "");
            if (fileExtension.length() > 10) {
                fileExtension = fileExtension.substring(0, 10);
            }

            String storedFilename = UUID.randomUUID().toString() + fileExtension;
            Path filePath = uploadPath.resolve(storedFilename).normalize();
            if (!filePath.startsWith(uploadPath.normalize())) {
                throw new IOException("Invalid file path");
            }

            Files.copy(file.getInputStream(), filePath);
            
            Attachment attachment = new Attachment();
            attachment.setFilename(storedFilename);
            attachment.setOriginalName(originalFilename);
            attachment.setFileType(file.getContentType());
            attachment.setFileSize(file.getSize());
            attachment.setFilePath(filePath.toString());
            attachment.setUploadedBy(uploadedBy);
            
            attachments.add(attachmentRepository.save(attachment));
        }
        
        return attachments;
    }
    
    public Path getFilePath(Long attachmentId) throws IOException {
        Attachment attachment = attachmentRepository.findById(attachmentId)
            .orElseThrow(() -> new IOException("Attachment not found"));
        return Paths.get(attachment.getFilePath());
    }
    
    public Attachment getAttachment(Long id) {
        return attachmentRepository.findById(id).orElse(null);
    }
}