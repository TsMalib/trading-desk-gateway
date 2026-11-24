package dev.belis.trader.desk.telegram;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.belis.trader.desk.config.DeskConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.util.List;
import java.util.function.Supplier;

/**
 * Sends to and reads from the owner's Telegram chat. Every call goes to {@code desk.telegram.chatId}.
 * Tests replace this bean with a recording mock.
 *
 * <p>Errors become {@link TelegramException} with the bot token redacted and no cause attached (a transport
 * exception may carry the request URL, which contains the token).
 */
@ApplicationScoped
public class TelegramClient {

    public static final String HTML = "HTML";
    private static final List<String> ALLOWED_UPDATES = List.of("message", "callback_query");
    private static final Tg.LinkPreviewOptions NO_PREVIEW = new Tg.LinkPreviewOptions(true);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final TelegramApi api;
    private final TelegramPollApi pollApi;
    private final DeskConfig config;

    public TelegramClient(@RestClient TelegramApi api, @RestClient TelegramPollApi pollApi, DeskConfig config) {
        this.api = api;
        this.pollApi = pollApi;
        this.config = config;
    }

    /** Long poll: waits up to {@code timeoutSeconds} for updates after {@code offset} (null: from the oldest). */
    public List<Tg.Update> getUpdates(Long offset, int timeoutSeconds) {
        List<Tg.Update> r = call("getUpdates",
                () -> pollApi.getUpdates(token(), new Tg.GetUpdates(offset, timeoutSeconds, ALLOWED_UPDATES)));
        return r == null ? List.of() : r;
    }

    /** Sends an HTML message to the owner's chat; returns its message_id. */
    public long send(String html, Tg.InlineKeyboardMarkup keyboard) {
        Tg.Message m = call("sendMessage", () -> api.sendMessage(token(),
                new Tg.SendMessage(config.telegram().chatId(), html, HTML, NO_PREVIEW, keyboard)));
        if (m == null) {
            throw new TelegramException("sendMessage returned no message");
        }
        return m.messageId();
    }

    /** Replaces the text of a message in the owner's chat; a null keyboard removes the buttons. */
    public void edit(long messageId, String html, Tg.InlineKeyboardMarkup keyboard) {
        try {
            call("editMessageText", () -> api.editMessageText(token(),
                    new Tg.EditMessageText(config.telegram().chatId(), messageId, html, HTML, NO_PREVIEW, keyboard)));
        } catch (TelegramException e) {
            if (e.getMessage() != null && e.getMessage().contains("message is not modified")) {
                return; // same text and keyboard: nothing to do
            }
            throw e;
        }
    }

    /** Stops the button's loading spinner; {@code text} shows as a toast (or a dialog when {@code alert}). */
    public void answer(String callbackQueryId, String text, boolean alert) {
        call("answerCallbackQuery", () -> api.answerCallbackQuery(token(),
                new Tg.AnswerCallbackQuery(callbackQueryId, text, alert ? Boolean.TRUE : null)));
    }

    // ---------------------------------------------------------------------------------------------------

    private String token() {
        return config.telegram().token();
    }

    private <T> T call(String method, Supplier<Tg.Response<T>> request) {
        Tg.Response<T> r;
        try {
            r = request.get();
        } catch (WebApplicationException e) {
            r = errorBody(e);
            if (r == null) {
                throw new TelegramException(method + ": HTTP " + e.getResponse().getStatus());
            }
        } catch (RuntimeException e) {
            throw new TelegramException(method + ": " + redact(e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
        if (r == null) {
            throw new TelegramException(method + ": empty response");
        }
        if (!r.ok()) {
            Integer retryAfter = r.parameters() == null ? null : r.parameters().retryAfter();
            throw new TelegramException(method + ": " + r.errorCode() + " " + redact(r.description())
                    + (retryAfter == null ? "" : " (retry after " + retryAfter + " s)"));
        }
        return r.result();
    }

    /** Telegram answers errors with a JSON body {ok:false, error_code, description}. */
    @SuppressWarnings("unchecked")
    private static <T> Tg.Response<T> errorBody(WebApplicationException e) {
        try {
            String body = e.getResponse().readEntity(String.class);
            return body == null || body.isBlank() ? null : JSON.readValue(body, Tg.Response.class);
        } catch (RuntimeException | java.io.IOException ignored) {
            return null;
        }
    }

    /** Removes the bot token from text that may contain a request URL. */
    String redact(String text) {
        String t = token();
        return text == null || t == null || t.isEmpty() ? text : text.replace(t, "<token>");
    }
}
