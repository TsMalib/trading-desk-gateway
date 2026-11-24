package dev.belis.trader.desk.telegram;

/** A Telegram call failed. The message never contains the bot token. */
public class TelegramException extends RuntimeException {

    public TelegramException(String message) {
        super(message);
    }
}
