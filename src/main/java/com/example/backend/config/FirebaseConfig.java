package com.example.backend.config;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;

import jakarta.annotation.PostConstruct;

@Configuration
public class FirebaseConfig {

    private final Environment env;

    public FirebaseConfig(Environment env) {
        this.env = env;
    }

    @PostConstruct
    public void initFirebase() throws IOException {
        if (FirebaseApp.getApps().isEmpty()) {

            // Check if Firebase is enabled
            String firebaseEnabled = env.getProperty("firebase.enabled", "true");
            if (firebaseEnabled.equalsIgnoreCase("false")) {
                return;
            }

            // Get credentials from application.properties
            String projectId = env.getProperty("firebase.project-id");
            String privateKey = env.getProperty("firebase.private-key");
            String clientEmail = env.getProperty("firebase.client-email");
            String databaseUrl = env.getProperty("firebase.database-url");

            // Check if all required variables are present
            if (projectId == null || privateKey == null || clientEmail == null) {
                return;
            }

            // IMPORTANT: Clean up the private key
            // In .properties files, \n becomes \\n, so we need to convert \\n to \n
            privateKey = privateKey
                .replace("\\n", "\n")  // Convert escaped newlines to actual newlines
                .replace("\"", "");     // Remove any quotes

            // Validate private key format
            if (!privateKey.contains("BEGIN PRIVATE KEY") || !privateKey.contains("END PRIVATE KEY")) {
                return;
            }

            // Get optional variables with defaults
            String privateKeyId = env.getProperty("firebase.private-key-id", "not-provided");
            String clientId = env.getProperty("firebase.client-id", "not-provided");
            String authUri = env.getProperty("firebase.auth-uri", "https://accounts.google.com/o/oauth2/auth");
            String tokenUri = env.getProperty("firebase.token-uri", "https://oauth2.googleapis.com/token");
            String authProviderCertUrl = env.getProperty("firebase.auth-provider-cert-url", 
                "https://www.googleapis.com/oauth2/v1/certs");
            String clientCertUrl = env.getProperty("firebase.client-cert-url",
                String.format("https://www.googleapis.com/robot/v1/metadata/x509/%s", 
                    clientEmail.replace("@", "%40")));

            // Build the service account JSON
            String serviceAccountJson = String.format(
                "{" +
                "  \"type\": \"service_account\"," +
                "  \"project_id\": \"%s\"," +
                "  \"private_key_id\": \"%s\"," +
                "  \"private_key\": \"%s\"," +
                "  \"client_email\": \"%s\"," +
                "  \"client_id\": \"%s\"," +
                "  \"auth_uri\": \"%s\"," +
                "  \"token_uri\": \"%s\"," +
                "  \"auth_provider_x509_cert_url\": \"%s\"," +
                "  \"client_x509_cert_url\": \"%s\"" +
                "}",
                projectId,
                privateKeyId,
                privateKey,
                clientEmail,
                clientId,
                authUri,
                tokenUri,
                authProviderCertUrl,
                clientCertUrl
            );

            // Create credentials from the JSON
            GoogleCredentials credentials = GoogleCredentials.fromStream(
                new ByteArrayInputStream(serviceAccountJson.getBytes(StandardCharsets.UTF_8))
            );

            // Build Firebase options
            FirebaseOptions.Builder optionsBuilder = FirebaseOptions.builder()
                .setCredentials(credentials);

            // Add database URL if provided
            if (databaseUrl != null && !databaseUrl.isEmpty()) {
                optionsBuilder.setDatabaseUrl(databaseUrl);
            }

            FirebaseOptions options = optionsBuilder.build();
            FirebaseApp.initializeApp(options);

         }
    }
}