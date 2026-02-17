package com.gmail.mcp;

import com.google.api.client.auth.oauth2.BearerToken;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartBody;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GmailClientTest {

    private static GmailClient client;

    @BeforeAll
    static void setUp() {
        var credential = new Credential(BearerToken.authorizationHeaderAccessMethod());
        client = new GmailClient(credential, new NetHttpTransport(), GsonFactory.getDefaultInstance());
    }

    // --- stripHtml ---

    @Test
    void stripHtml_plainTextPassthrough() {
        assertEquals("Hello world", client.stripHtml("Hello world"));
    }

    @Test
    void stripHtml_removesParagraphTags() {
        String result = client.stripHtml("<p>First</p><p>Second</p>");
        assertTrue(result.contains("First"));
        assertTrue(result.contains("Second"));
        assertFalse(result.contains("<p>"));
    }

    @Test
    void stripHtml_handlesBrTags() {
        String result = client.stripHtml("Line1<br>Line2");
        assertTrue(result.contains("Line1"));
        assertTrue(result.contains("Line2"));
        assertFalse(result.contains("<br>"));
    }

    @Test
    void stripHtml_removesNestedTags() {
        assertEquals("bold italic", client.stripHtml("<div><b>bold</b> <i>italic</i></div>"));
    }

    @Test
    void stripHtml_collapsesTripleNewlines() {
        String html = "<p>A</p><p></p><p></p><p>B</p>";
        String result = client.stripHtml(html);
        assertFalse(result.contains("\n\n\n"), "should not contain 3+ consecutive newlines");
    }

    @Test
    void stripHtml_removesScriptTags() {
        String result = client.stripHtml("<p>Hello</p><script>alert('xss')</script><p>World</p>");
        assertFalse(result.contains("alert"));
        assertTrue(result.contains("Hello"));
        assertTrue(result.contains("World"));
    }

    @Test
    void stripHtml_decodesHtmlEntities() {
        assertEquals("A & B", client.stripHtml("A &amp; B"));
    }

    @Test
    void stripHtml_emptyInput() {
        assertEquals("", client.stripHtml(""));
    }

    @Test
    void stripHtml_whitespaceOnly() {
        assertEquals("", client.stripHtml("   "));
    }

    @Test
    void stripHtml_unicode() {
        String result = client.stripHtml("<p>Caf\u00e9</p>");
        assertTrue(result.contains("Caf\u00e9"));
        assertFalse(result.contains("<p>"));
    }

    // --- decodeBase64Url ---

    @Test
    void decodeBase64Url_validString() {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("Hello, World!".getBytes(StandardCharsets.UTF_8));
        assertEquals("Hello, World!", client.decodeBase64Url(encoded));
    }

    @Test
    void decodeBase64Url_urlSafeChars() {
        // Use a string whose base64 encoding contains URL-safe chars (- and _)
        String original = "subjects?q=1&r=2";
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(original.getBytes(StandardCharsets.UTF_8));
        assertEquals(original, client.decodeBase64Url(encoded));
    }

    @Test
    void decodeBase64Url_emptyInput() {
        assertEquals("", client.decodeBase64Url(""));
    }

    @Test
    void decodeBase64Url_utf8Multibyte() {
        String original = "\u00e9\u00e0\u00fc \u2603 \ud83d\ude00";
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(original.getBytes(StandardCharsets.UTF_8));
        assertEquals(original, client.decodeBase64Url(encoded));
    }

    @Test
    void decodeBase64Url_invalidInputThrows() {
        assertThrows(IllegalArgumentException.class, () -> client.decodeBase64Url("!!!invalid!!!"));
    }

    // --- findBodyByMimeType ---

    @Test
    void findBodyByMimeType_singlePartMatch() {
        MessagePart part = buildPart("text/plain", encode("Hello"));
        assertEquals("Hello", client.findBodyByMimeType(part, "text/plain"));
    }

    @Test
    void findBodyByMimeType_singlePartMismatch() {
        MessagePart part = buildPart("text/html", encode("<b>Hi</b>"));
        assertNull(client.findBodyByMimeType(part, "text/plain"));
    }

    @Test
    void findBodyByMimeType_multipartWithTarget() {
        MessagePart plain = buildPart("text/plain", encode("Plain text"));
        MessagePart html = buildPart("text/html", encode("<p>HTML</p>"));
        MessagePart container = new MessagePart()
                .setMimeType("multipart/alternative")
                .setParts(List.of(plain, html));

        assertEquals("Plain text", client.findBodyByMimeType(container, "text/plain"));
        assertEquals("<p>HTML</p>", client.findBodyByMimeType(container, "text/html"));
    }

    @Test
    void findBodyByMimeType_deeplyNested() {
        MessagePart plain = buildPart("text/plain", encode("Deep"));
        MessagePart inner = new MessagePart()
                .setMimeType("multipart/alternative")
                .setParts(List.of(plain));
        MessagePart outer = new MessagePart()
                .setMimeType("multipart/mixed")
                .setParts(List.of(inner));

        assertEquals("Deep", client.findBodyByMimeType(outer, "text/plain"));
    }

    @Test
    void findBodyByMimeType_nullPart() {
        assertNull(client.findBodyByMimeType(null, "text/plain"));
    }

    @Test
    void findBodyByMimeType_nullBody() {
        MessagePart part = new MessagePart().setMimeType("text/plain").setBody(null);
        assertNull(client.findBodyByMimeType(part, "text/plain"));
    }

    @Test
    void findBodyByMimeType_nullBodyData() {
        MessagePart part = new MessagePart()
                .setMimeType("text/plain")
                .setBody(new MessagePartBody().setData(null));
        assertNull(client.findBodyByMimeType(part, "text/plain"));
    }

    @Test
    void findBodyByMimeType_nullPartsList() {
        MessagePart part = new MessagePart()
                .setMimeType("multipart/alternative")
                .setParts(null);
        assertNull(client.findBodyByMimeType(part, "text/plain"));
    }

    // --- extractBody ---

    @Test
    void extractBody_prefersPlainTextOverHtml() {
        MessagePart plain = buildPart("text/plain", encode("Plain version"));
        MessagePart html = buildPart("text/html", encode("<b>HTML version</b>"));
        MessagePart container = new MessagePart()
                .setMimeType("multipart/alternative")
                .setParts(List.of(plain, html));

        assertEquals("Plain version", client.extractBody(container));
    }

    @Test
    void extractBody_fallsBackToHtml() {
        MessagePart html = buildPart("text/html", encode("<p>HTML only</p>"));
        MessagePart container = new MessagePart()
                .setMimeType("multipart/alternative")
                .setParts(List.of(html));

        String result = client.extractBody(container);
        assertNotNull(result);
        assertTrue(result.contains("HTML only"));
        assertFalse(result.contains("<p>"), "HTML tags should be stripped");
    }

    @Test
    void extractBody_neitherReturnsNull() {
        MessagePart attachment = buildPart("application/pdf", encode("binary"));
        MessagePart container = new MessagePart()
                .setMimeType("multipart/mixed")
                .setParts(List.of(attachment));

        assertNull(client.extractBody(container));
    }

    @Test
    void extractBody_nullPartReturnsNull() {
        assertNull(client.extractBody(null));
    }

    // --- extractAttachments ---

    @Test
    void extractAttachments_noAttachments() {
        MessagePart plain = buildPart("text/plain", encode("Hello"));
        MessagePart container = new MessagePart()
                .setMimeType("multipart/mixed")
                .setParts(List.of(plain));

        var attachments = client.extractAttachments(container);
        assertTrue(attachments.isEmpty());
    }

    @Test
    void extractAttachments_singleAttachment() {
        MessagePart plain = buildPart("text/plain", encode("Body text"));
        MessagePart attachment = buildAttachmentPart("report.pdf", "application/pdf", 1024);
        MessagePart container = new MessagePart()
                .setMimeType("multipart/mixed")
                .setParts(List.of(plain, attachment));

        var attachments = client.extractAttachments(container);
        assertEquals(1, attachments.size());
        assertEquals(0, attachments.get(0).index());
        assertEquals("report.pdf", attachments.get(0).filename());
        assertEquals("application/pdf", attachments.get(0).mimeType());
        assertEquals(1024, attachments.get(0).sizeBytes());
    }

    @Test
    void extractAttachments_multipleAttachments() {
        MessagePart plain = buildPart("text/plain", encode("Body"));
        MessagePart att1 = buildAttachmentPart("doc.pdf", "application/pdf", 2048);
        MessagePart att2 = buildAttachmentPart("image.png", "image/png", 4096);
        MessagePart att3 = buildAttachmentPart("notes.txt", "text/plain", 512);
        MessagePart container = new MessagePart()
                .setMimeType("multipart/mixed")
                .setParts(List.of(plain, att1, att2, att3));

        var attachments = client.extractAttachments(container);
        assertEquals(3, attachments.size());
        assertEquals(0, attachments.get(0).index());
        assertEquals("doc.pdf", attachments.get(0).filename());
        assertEquals(1, attachments.get(1).index());
        assertEquals("image.png", attachments.get(1).filename());
        assertEquals(2, attachments.get(2).index());
        assertEquals("notes.txt", attachments.get(2).filename());
    }

    @Test
    void extractAttachments_nestedMultipart() {
        MessagePart plain = buildPart("text/plain", encode("Body"));
        MessagePart att1 = buildAttachmentPart("inner.txt", "text/plain", 100);
        MessagePart inner = new MessagePart()
                .setMimeType("multipart/alternative")
                .setParts(List.of(plain, att1));
        MessagePart att2 = buildAttachmentPart("outer.pdf", "application/pdf", 500);
        MessagePart outer = new MessagePart()
                .setMimeType("multipart/mixed")
                .setParts(List.of(inner, att2));

        var attachments = client.extractAttachments(outer);
        assertEquals(2, attachments.size());
        assertEquals("inner.txt", attachments.get(0).filename());
        assertEquals("outer.pdf", attachments.get(1).filename());
    }

    @Test
    void extractAttachments_emptyAndNullFilenamesExcluded() {
        MessagePart noFilename = new MessagePart()
                .setMimeType("application/octet-stream")
                .setBody(new MessagePartBody().setSize(100));
        // filename is null by default

        MessagePart emptyFilename = new MessagePart()
                .setMimeType("application/octet-stream")
                .setFilename("")
                .setBody(new MessagePartBody().setSize(200));

        MessagePart validAtt = buildAttachmentPart("valid.txt", "text/plain", 300);

        MessagePart container = new MessagePart()
                .setMimeType("multipart/mixed")
                .setParts(List.of(noFilename, emptyFilename, validAtt));

        var attachments = client.extractAttachments(container);
        assertEquals(1, attachments.size());
        assertEquals("valid.txt", attachments.get(0).filename());
    }

    @Test
    void extractAttachments_inlineVsSeparateAttachments() {
        // Inline: data present directly
        MessagePart inline = new MessagePart()
                .setMimeType("text/plain")
                .setFilename("inline.txt")
                .setBody(new MessagePartBody().setData(encode("inline content")).setSize(14));

        // Separate: attachmentId present, no inline data
        MessagePart separate = new MessagePart()
                .setMimeType("text/plain")
                .setFilename("separate.txt")
                .setBody(new MessagePartBody().setAttachmentId("att-id-123").setSize(500));

        MessagePart container = new MessagePart()
                .setMimeType("multipart/mixed")
                .setParts(List.of(inline, separate));

        var attachments = client.extractAttachments(container);
        assertEquals(2, attachments.size());
        assertEquals("inline.txt", attachments.get(0).filename());
        assertEquals("separate.txt", attachments.get(1).filename());
    }

    @Test
    void extractAttachments_nullRoot() {
        var attachments = client.extractAttachments(null);
        assertTrue(attachments.isEmpty());
    }

    // --- sanitizeFilename ---

    @Test
    void sanitizeFilename_normalName() {
        assertEquals("report.pdf", GmailClient.sanitizeFilename("report.pdf"));
    }

    @Test
    void sanitizeFilename_pathTraversal() {
        assertEquals("passwd", GmailClient.sanitizeFilename("../../etc/passwd"));
    }

    @Test
    void sanitizeFilename_windowsTraversal() {
        assertEquals("system.ini", GmailClient.sanitizeFilename("..\\..\\Windows\\system.ini"));
    }

    @Test
    void sanitizeFilename_hiddenFile() {
        assertEquals("bashrc", GmailClient.sanitizeFilename(".bashrc"));
    }

    @Test
    void sanitizeFilename_specialChars() {
        assertEquals("file__name__1_.txt", GmailClient.sanitizeFilename("file (name) 1!.txt"));
    }

    @Test
    void sanitizeFilename_unicode() {
        assertEquals("caf_", GmailClient.sanitizeFilename("caf\u00e9"));
    }

    @Test
    void sanitizeFilename_emptyString() {
        assertEquals("attachment", GmailClient.sanitizeFilename(""));
    }

    @Test
    void sanitizeFilename_nullInput() {
        assertEquals("attachment", GmailClient.sanitizeFilename(null));
    }

    @Test
    void sanitizeFilename_longName() {
        String longName = "a".repeat(300) + ".txt";
        String result = GmailClient.sanitizeFilename(longName);
        assertTrue(result.length() <= 200);
    }

    @Test
    void sanitizeFilename_onlyDots() {
        assertEquals("attachment", GmailClient.sanitizeFilename("..."));
    }

    @Test
    void sanitizeFilename_onlySlashes() {
        assertEquals("attachment", GmailClient.sanitizeFilename("///"));
    }

    @Test
    void sanitizeFilename_mixedPathSeparators() {
        assertEquals("file.txt", GmailClient.sanitizeFilename("/path/to\\dir/file.txt"));
    }

    // --- Helpers ---

    private static String encode(String text) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static MessagePart buildPart(String mimeType, String base64Data) {
        return new MessagePart()
                .setMimeType(mimeType)
                .setBody(new MessagePartBody().setData(base64Data));
    }

    private static MessagePart buildAttachmentPart(String filename, String mimeType, int size) {
        return new MessagePart()
                .setMimeType(mimeType)
                .setFilename(filename)
                .setBody(new MessagePartBody().setSize(size));
    }
}
