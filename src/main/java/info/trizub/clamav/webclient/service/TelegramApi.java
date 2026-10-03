package info.trizub.clamav.webclient.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;

/**
 * The Telegram Bot API, as the console uses it. Every answer is read whatever
 * its HTTP status: Telegram explains a refusal in the JSON body ("message is
 * not modified", "bad request: BUTTON_URL_INVALID"...), and that text is what
 * makes a failure diagnosable from the log.
 */
@Component
public class TelegramApi {

    public static class TelegramException extends Exception {
        private final int code;
        public TelegramException(int code, String description) {
            super(description);
            this.code = code;
        }
        public int getCode() { return code; }
    }

    private final SettingsService settings;
    private final ObjectMapper mapper;
    private final String apiBase;
    private final RestClient http;

    public TelegramApi(SettingsService settings, ObjectMapper mapper,
                       @Value("${app.telegram.apiBase:https://api.telegram.org}") String apiBase) {
        this.settings = settings;
        this.mapper = mapper;
        this.apiBase = apiBase.endsWith("/") ? apiBase.substring(0, apiBase.length() - 1) : apiBase;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        // Above getUpdates' long-poll timeout (25 s), or every idle poll would
        // end in a read timeout instead of an empty answer.
        factory.setReadTimeout(40_000);
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    public boolean configured() {
        return !settings.telegramBotToken().isBlank() && !settings.telegramChatId().isBlank();
    }

    /** Calls a Bot API method and returns its "result"; throws with Telegram's own description on refusal. */
    public JsonNode call(String method, Object payload) throws TelegramException {
        String url = apiBase + "/bot" + settings.telegramBotToken() + "/" + method;
        String body;
        try {
            body = http.post().uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(mapper.writeValueAsString(payload))
                    .exchange((req, res) -> new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new TelegramException(0, "cannot reach Telegram: " + e.getMessage());
        }
        try {
            JsonNode node = mapper.readTree(body);
            if (!node.path("ok").asBoolean(false)) {
                throw new TelegramException(node.path("error_code").asInt(0), node.path("description").asText(body));
            }
            return node.path("result");
        } catch (TelegramException e) {
            throw e;
        } catch (Exception e) {
            throw new TelegramException(0, "unreadable answer from Telegram: " + body);
        }
    }
}
