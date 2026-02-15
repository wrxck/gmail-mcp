package com.gmail.mcp;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.gmail.GmailScopes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.util.List;

public class GmailAuth {

    private static final Logger log = LoggerFactory.getLogger(GmailAuth.class);
    private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    private static final List<String> SCOPES = List.of(GmailScopes.GMAIL_READONLY);
    private static final String CREDENTIAL_USER_ID = "user";

    private static final Path CONFIG_DIR;
    private static final Path CLIENT_SECRET_FILE;
    private static final Path TOKENS_DIR;

    static {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            throw new IllegalStateException("user.home system property is not set");
        }
        CONFIG_DIR = Path.of(home, ".gmail-mcp");
        CLIENT_SECRET_FILE = CONFIG_DIR.resolve("client_secret.json");
        TOKENS_DIR = CONFIG_DIR.resolve("tokens");
    }

    private static NetHttpTransport httpTransport;

    public static Credential authorize() throws IOException, GeneralSecurityException {
        ensureConfigDir();

        if (!Files.exists(CLIENT_SECRET_FILE)) {
            throw new IOException("client_secret.json not found at " + CLIENT_SECRET_FILE
                    + " — download it from Google Cloud Console");
        }

        GoogleClientSecrets clientSecrets;
        try (var reader = new FileReader(CLIENT_SECRET_FILE.toFile(), StandardCharsets.UTF_8)) {
            clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, reader);
        }

        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                getHttpTransport(), JSON_FACTORY, clientSecrets, SCOPES)
                .setDataStoreFactory(new FileDataStoreFactory(TOKENS_DIR.toFile()))
                .setAccessType("offline")
                .build();

        LocalServerReceiver receiver = new LocalServerReceiver.Builder()
                .setPort(0)
                .build();

        Credential credential = new AuthorizationCodeInstalledApp(flow, receiver)
                .authorize(CREDENTIAL_USER_ID);
        log.info("Authorization successful");
        return credential;
    }

    public static Credential getCredential() throws IOException, GeneralSecurityException {
        String[] files = TOKENS_DIR.toFile().list();
        if (!Files.exists(TOKENS_DIR) || files == null || files.length == 0) {
            throw new IOException("No stored credentials found. Run with --auth first.");
        }
        return authorize();
    }

    public static synchronized NetHttpTransport getHttpTransport()
            throws GeneralSecurityException, IOException {
        if (httpTransport == null) {
            httpTransport = GoogleNetHttpTransport.newTrustedTransport();
        }
        return httpTransport;
    }

    public static JsonFactory getJsonFactory() {
        return JSON_FACTORY;
    }

    private static void ensureConfigDir() throws IOException {
        if (!Files.exists(CONFIG_DIR)) {
            Files.createDirectories(CONFIG_DIR,
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rwx------")));
        }
    }
}
