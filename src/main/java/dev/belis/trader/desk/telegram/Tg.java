package dev.belis.trader.desk.telegram;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

/** The parts of the Telegram Bot API this service uses (https://core.telegram.org/bots/api). */
public final class Tg {

    private Tg() {
    }

    // ---- responses ----------------------------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Response<T>(boolean ok, T result, String description, Integer errorCode,
                              ResponseParameters parameters) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ResponseParameters(Integer retryAfter) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Update(long updateId, Message message, CallbackQuery callbackQuery) {
    }

    /** @param date unix time in seconds */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Message(long messageId, User from, Chat chat, Long date, String text) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record User(long id, Boolean isBot, String username) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Chat(long id, String type) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CallbackQuery(String id, User from, Message message, String data) {
    }

    // ---- keyboards ----------------------------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record InlineKeyboardMarkup(List<List<InlineKeyboardButton>> inlineKeyboard) {

        /** One row of buttons. */
        public static InlineKeyboardMarkup row(InlineKeyboardButton... buttons) {
            return new InlineKeyboardMarkup(List.of(List.of(buttons)));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record InlineKeyboardButton(String text, String callbackData) {
    }

    // ---- requests -----------------------------------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record GetUpdates(Long offset, Integer timeout, List<String> allowedUpdates) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record LinkPreviewOptions(Boolean isDisabled) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SendMessage(long chatId, String text, String parseMode, LinkPreviewOptions linkPreviewOptions,
                              InlineKeyboardMarkup replyMarkup) {
    }

    /** Without replyMarkup the message's inline keyboard is removed. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EditMessageText(long chatId, long messageId, String text, String parseMode,
                                  LinkPreviewOptions linkPreviewOptions, InlineKeyboardMarkup replyMarkup) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AnswerCallbackQuery(String callbackQueryId, String text, Boolean showAlert) {
    }
}
