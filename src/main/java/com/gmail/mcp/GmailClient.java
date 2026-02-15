package com.gmail.mcp;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Label;
import com.google.api.services.gmail.model.ListLabelsResponse;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartHeader;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class GmailClient {

    private static final Logger log = LoggerFactory.getLogger(GmailClient.class);
    private static final Set<String> METADATA_HEADERS = Set.of("From", "To", "Subject", "Date");
    private static final int MAX_RESULTS_LIMIT = 100;

    private final Gmail service;

    public GmailClient(Credential credential, NetHttpTransport httpTransport, JsonFactory jsonFactory) {
        this.service = new Gmail.Builder(httpTransport, jsonFactory, credential)
                .setApplicationName("gmail-mcp")
                .build();
    }

    public List<Map<String, Object>> listMessages(String query, int maxResults) throws IOException {
        maxResults = Math.max(1, Math.min(maxResults, MAX_RESULTS_LIMIT));

        Gmail.Users.Messages.List request = service.users().messages().list("me")
                .setMaxResults((long) maxResults);

        if (query != null && !query.isBlank()) {
            request.setQ(query);
        }

        ListMessagesResponse response = request.execute();
        List<Message> messages = response.getMessages();
        if (messages == null || messages.isEmpty()) {
            return Collections.emptyList();
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (Message msg : messages) {
            Message metadata = service.users().messages().get("me", msg.getId())
                    .setFormat("metadata")
                    .setMetadataHeaders(List.copyOf(METADATA_HEADERS))
                    .execute();

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", metadata.getId());
            entry.put("threadId", metadata.getThreadId());
            entry.put("snippet", metadata.getSnippet());
            entry.put("from", "");
            entry.put("to", "");
            entry.put("subject", "");
            entry.put("date", "");

            if (metadata.getPayload() != null && metadata.getPayload().getHeaders() != null) {
                for (MessagePartHeader header : metadata.getPayload().getHeaders()) {
                    if (METADATA_HEADERS.contains(header.getName())) {
                        entry.put(header.getName().toLowerCase(), header.getValue());
                    }
                }
            }

            results.add(entry);
        }
        return results;
    }

    public Map<String, Object> getMessage(String id) throws IOException {
        Message message = service.users().messages().get("me", id)
                .setFormat("full")
                .execute();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", message.getId());
        result.put("threadId", message.getThreadId());
        result.put("from", "");
        result.put("to", "");
        result.put("subject", "");
        result.put("date", "");

        if (message.getPayload() != null && message.getPayload().getHeaders() != null) {
            for (MessagePartHeader header : message.getPayload().getHeaders()) {
                if (METADATA_HEADERS.contains(header.getName())) {
                    result.put(header.getName().toLowerCase(), header.getValue());
                }
            }
        }

        String body = extractBody(message.getPayload());
        result.put("body", body != null ? body : "");

        if (message.getLabelIds() != null) {
            result.put("labels", message.getLabelIds());
        }

        return result;
    }

    public List<Map<String, Object>> searchMessages(String query, int maxResults) throws IOException {
        return listMessages(query, maxResults);
    }

    public List<Map<String, Object>> listLabels() throws IOException {
        ListLabelsResponse response = service.users().labels().list("me").execute();
        List<Label> labels = response.getLabels();
        if (labels == null || labels.isEmpty()) {
            return Collections.emptyList();
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (Label label : labels) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", label.getId());
            entry.put("name", label.getName());
            entry.put("type", label.getType());
            results.add(entry);
        }
        return results;
    }

    private String extractBody(MessagePart part) {
        if (part == null) {
            return null;
        }

        String plainText = findBodyByMimeType(part, "text/plain");
        if (plainText != null) {
            return plainText;
        }

        String htmlText = findBodyByMimeType(part, "text/html");
        if (htmlText != null) {
            return stripHtml(htmlText);
        }

        return null;
    }

    private String findBodyByMimeType(MessagePart part, String targetMimeType) {
        if (part == null) {
            return null;
        }

        if (targetMimeType.equals(part.getMimeType())
                && part.getBody() != null && part.getBody().getData() != null) {
            return decodeBase64Url(part.getBody().getData());
        }

        if (part.getParts() != null) {
            for (MessagePart subPart : part.getParts()) {
                String result = findBodyByMimeType(subPart, targetMimeType);
                if (result != null) {
                    return result;
                }
            }
        }

        return null;
    }

    private String decodeBase64Url(String data) {
        return new String(Base64.getUrlDecoder().decode(data), StandardCharsets.UTF_8);
    }

    private String stripHtml(String html) {
        Document doc = Jsoup.parse(html);
        doc.outputSettings().prettyPrint(false);
        doc.select("br").after("\n");
        doc.select("p").after("\n");
        return doc.text().replaceAll("\n{3,}", "\n\n").trim();
    }
}
