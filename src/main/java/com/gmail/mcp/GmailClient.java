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
import com.google.api.services.gmail.model.MessagePartBody;
import com.google.api.services.gmail.model.MessagePartHeader;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class GmailClient {

    record AttachmentInfo(int index, String filename, String mimeType, int sizeBytes, MessagePart part) {}

    sealed interface AttachmentResult permits TextAttachmentResult, ImageAttachmentResult, SavedFileAttachmentResult {
        String messageId(); int index(); String filename(); String mimeType(); int sizeBytes();
    }
    record TextAttachmentResult(String messageId, int index, String filename, String mimeType, int sizeBytes, String content) implements AttachmentResult {}
    record ImageAttachmentResult(String messageId, int index, String filename, String mimeType, int sizeBytes, String base64Data, String filePath) implements AttachmentResult {}
    record SavedFileAttachmentResult(String messageId, int index, String filename, String mimeType, int sizeBytes, String filePath) implements AttachmentResult {}

    private static final Logger log = LoggerFactory.getLogger(GmailClient.class);
    private static final Set<String> METADATA_HEADERS = Set.of("From", "To", "Subject", "Date");
    private static final int MAX_RESULTS_LIMIT = 100;
    private static final int MAX_IMAGE_SIZE_BYTES = 10 * 1024 * 1024;

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

        List<AttachmentInfo> attachments = extractAttachments(message.getPayload());
        if (!attachments.isEmpty()) {
            List<Map<String, Object>> attachmentList = new ArrayList<>();
            for (AttachmentInfo att : attachments) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("index", att.index());
                entry.put("filename", att.filename());
                entry.put("mimeType", att.mimeType());
                entry.put("sizeBytes", att.sizeBytes());
                attachmentList.add(entry);
            }
            result.put("attachments", attachmentList);
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

    List<AttachmentInfo> extractAttachments(MessagePart root) {
        List<AttachmentInfo> attachments = new ArrayList<>();
        collectAttachments(root, attachments);
        return attachments;
    }

    private void collectAttachments(MessagePart part, List<AttachmentInfo> result) {
        if (part == null) {
            return;
        }

        String filename = part.getFilename();
        if (filename != null && !filename.isEmpty()) {
            int size = 0;
            if (part.getBody() != null && part.getBody().getSize() != null) {
                size = part.getBody().getSize();
            }
            result.add(new AttachmentInfo(
                    result.size(), filename, part.getMimeType(), size, part));
        }

        if (part.getParts() != null) {
            for (MessagePart child : part.getParts()) {
                collectAttachments(child, result);
            }
        }
    }

    public AttachmentResult getAttachmentContent(String messageId, int attachmentIndex, Path attachmentsBaseDir) throws IOException {
        if (messageId.contains("/") || messageId.contains("\\") || messageId.contains("..")) {
            throw new IllegalArgumentException("Invalid messageId");
        }

        Message message = service.users().messages().get("me", messageId)
                .setFormat("full")
                .execute();

        List<AttachmentInfo> attachments = extractAttachments(message.getPayload());
        if (attachmentIndex < 0 || attachmentIndex >= attachments.size()) {
            throw new IllegalArgumentException(
                    "Attachment index " + attachmentIndex + " out of range (0-" + (attachments.size() - 1) + ")");
        }

        AttachmentInfo att = attachments.get(attachmentIndex);

        if (att.mimeType().startsWith("text/")) {
            byte[] bytes = fetchAttachmentBytes(messageId, att);
            String data = bytes != null ? new String(bytes, StandardCharsets.UTF_8) : "";
            if ("text/html".equals(att.mimeType())) {
                data = stripHtml(data);
            }
            if (data.length() > ContentSanitizer.MAX_ATTACHMENT_LENGTH) {
                data = data.substring(0, ContentSanitizer.MAX_ATTACHMENT_LENGTH) + "\n[TRUNCATED]";
            }
            return new TextAttachmentResult(messageId, att.index(), att.filename(), att.mimeType(), att.sizeBytes(), data);
        }

        byte[] bytes = fetchAttachmentBytes(messageId, att);
        if (bytes == null) {
            bytes = new byte[0];
        }

        String savedPath = saveAttachmentToDisk(bytes, messageId, att.filename(), attachmentsBaseDir);

        if (att.mimeType().startsWith("image/") && att.sizeBytes() <= MAX_IMAGE_SIZE_BYTES && bytes.length > 0) {
            String base64Data = Base64.getEncoder().encodeToString(bytes);
            return new ImageAttachmentResult(messageId, att.index(), att.filename(), att.mimeType(), att.sizeBytes(), base64Data, savedPath);
        }

        return new SavedFileAttachmentResult(messageId, att.index(), att.filename(), att.mimeType(), att.sizeBytes(), savedPath);
    }

    private byte[] fetchAttachmentBytes(String messageId, AttachmentInfo att) throws IOException {
        MessagePartBody body = att.part().getBody();
        if (body != null && body.getData() != null) {
            return Base64.getUrlDecoder().decode(body.getData());
        }
        if (body != null && body.getAttachmentId() != null) {
            MessagePartBody fetched = service.users().messages().attachments()
                    .get("me", messageId, body.getAttachmentId())
                    .execute();
            if (fetched.getData() != null) {
                return Base64.getUrlDecoder().decode(fetched.getData());
            }
        }
        return null;
    }

    private String saveAttachmentToDisk(byte[] bytes, String messageId, String filename, Path attachmentsBaseDir) throws IOException {
        String safeFilename = sanitizeFilename(filename);
        Path messageDir = attachmentsBaseDir.resolve(messageId);
        Files.createDirectories(messageDir);

        try {
            Set<PosixFilePermission> ownerOnly = Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(attachmentsBaseDir, ownerOnly);
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystem (e.g., Windows)
        }

        Path filePath = messageDir.resolve(safeFilename);
        Files.write(filePath, bytes);
        return filePath.toString();
    }

    static String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "attachment";
        }
        int lastSlash = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        if (lastSlash >= 0) {
            filename = filename.substring(lastSlash + 1);
        }
        while (filename.startsWith(".")) {
            filename = filename.substring(1);
        }
        filename = filename.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (filename.length() > 200) {
            filename = filename.substring(0, 200);
        }
        if (filename.isEmpty()) {
            return "attachment";
        }
        return filename;
    }

    String extractBody(MessagePart part) {
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

    String findBodyByMimeType(MessagePart part, String targetMimeType) {
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

    String decodeBase64Url(String data) {
        return new String(Base64.getUrlDecoder().decode(data), StandardCharsets.UTF_8);
    }

    String stripHtml(String html) {
        Document doc = Jsoup.parse(html);
        doc.outputSettings().prettyPrint(false);
        doc.select("br").after("\n");
        doc.select("p").after("\n");
        return doc.text().replaceAll("\n{3,}", "\n\n").trim();
    }
}
