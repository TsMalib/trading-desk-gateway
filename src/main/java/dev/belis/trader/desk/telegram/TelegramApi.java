package dev.belis.trader.desk.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * Telegram Bot API over the Quarkus REST client (config key "telegram"). The token is part of the path, so
 * never log a URL or an exception message from this client without {@link TelegramClient#redact}.
 */
@RegisterRestClient(configKey = "telegram")
@Path("/bot{token}")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface TelegramApi {

    @POST
    @Path("/sendMessage")
    Tg.Response<Tg.Message> sendMessage(@PathParam("token") String token, Tg.SendMessage request);

    /** result is the edited Message, or true for inline messages. */
    @POST
    @Path("/editMessageText")
    Tg.Response<JsonNode> editMessageText(@PathParam("token") String token, Tg.EditMessageText request);

    @POST
    @Path("/answerCallbackQuery")
    Tg.Response<Boolean> answerCallbackQuery(@PathParam("token") String token, Tg.AnswerCallbackQuery request);
}
