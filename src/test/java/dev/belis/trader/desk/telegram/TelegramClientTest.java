package dev.belis.trader.desk.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import dev.belis.trader.desk.config.DeskConfig;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The real client against stub APIs: requests, error handling, and that the token never leaks. */
class TelegramClientTest {

    static final String TOKEN = "123456:SECRET-token";

    final List<Object> requests = new ArrayList<>();
    Tg.Response<?> next;
    RuntimeException throwNext;

    @SuppressWarnings("unchecked")
    <T> Tg.Response<T> respond(String token, Object request) {
        assertEquals(TOKEN, token);
        requests.add(request);
        if (throwNext != null) {
            throw throwNext;
        }
        return (Tg.Response<T>) next;
    }

    final TelegramApi api = new TelegramApi() {
        public Tg.Response<Tg.Message> sendMessage(String token, Tg.SendMessage r) {
            return respond(token, r);
        }

        public Tg.Response<JsonNode> editMessageText(String token, Tg.EditMessageText r) {
            return respond(token, r);
        }

        public Tg.Response<Boolean> answerCallbackQuery(String token, Tg.AnswerCallbackQuery r) {
            return respond(token, r);
        }
    };

    final TelegramPollApi pollApi = (token, r) -> respond(token, r);

    final TelegramClient client = new TelegramClient(api, pollApi, config());

    static DeskConfig config() {
        DeskConfig.Telegram telegram = (DeskConfig.Telegram) Proxy.newProxyInstance(
                DeskConfig.class.getClassLoader(), new Class<?>[]{DeskConfig.Telegram.class},
                (p, m, a) -> switch (m.getName()) {
                    case "token" -> TOKEN;
                    case "chatId" -> 777L;
                    case "userId" -> 2L;
                    case "polling" -> false;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        return (DeskConfig) Proxy.newProxyInstance(DeskConfig.class.getClassLoader(),
                new Class<?>[]{DeskConfig.class}, (p, m, a) -> switch (m.getName()) {
                    case "telegram" -> telegram;
                    case "mode" -> DeskConfig.Mode.PAPER;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    @Test
    void sendGoesToTheOwnersChatAsHtmlWithoutPreview() {
        next = new Tg.Response<>(true, new Tg.Message(55, null, null, null, null), null, null, null);
        Tg.InlineKeyboardMarkup k = ProposalMessages.keyboard(1);

        assertEquals(55, client.send("<b>hi</b>", k));

        Tg.SendMessage r = (Tg.SendMessage) requests.get(0);
        assertEquals(777L, r.chatId());
        assertEquals("HTML", r.parseMode());
        assertTrue(r.linkPreviewOptions().isDisabled());
        assertEquals(k, r.replyMarkup());
    }

    @Test
    void getUpdatesLongPollsForMessagesAndButtonsOnly() {
        next = new Tg.Response<>(true, List.of(), null, null, null);
        assertTrue(client.getUpdates(7L, 50).isEmpty());
        Tg.GetUpdates r = (Tg.GetUpdates) requests.get(0);
        assertEquals(7L, r.offset());
        assertEquals(50, r.timeout());
        assertEquals(List.of("message", "callback_query"), r.allowedUpdates());
    }

    @Test
    void notModifiedIsNotAnError() {
        next = new Tg.Response<>(false, null, "Bad Request: message is not modified", 400, null);
        client.edit(1, "same", null);
    }

    @Test
    void apiErrorsCarryTheDescriptionButNeverTheToken() {
        next = new Tg.Response<>(false, null, "Unauthorized for bot" + TOKEN, 401,
                new Tg.ResponseParameters(3));
        TelegramException e = assertThrows(TelegramException.class, () -> client.answer("q", "x", false));
        assertFalse(e.getMessage().contains(TOKEN), e.getMessage());
        assertTrue(e.getMessage().contains("401"), e.getMessage());
        assertTrue(e.getMessage().contains("retry after 3 s"), e.getMessage());
    }

    @Test
    void transportErrorsNeverLeakTheTokenOrTheCause() {
        throwNext = new IllegalStateException("Connection refused: https://api.telegram.org/bot" + TOKEN + "/sendMessage");
        TelegramException e = assertThrows(TelegramException.class, () -> client.send("x", null));
        assertFalse(e.getMessage().contains(TOKEN), e.getMessage());
        assertTrue(e.getMessage().contains("bot<token>/sendMessage"), e.getMessage());
        assertEquals(null, e.getCause());
    }
}
