package dev.belis.trader.desk.telegram;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.List;

/**
 * getUpdates only, on its own REST client (config key "telegram-poll") so its read timeout can exceed the
 * 50 s long poll while every other call ({@link TelegramApi}) keeps a short one.
 */
@RegisterRestClient(configKey = "telegram-poll")
@Path("/bot{token}")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface TelegramPollApi {

    @POST
    @Path("/getUpdates")
    Tg.Response<List<Tg.Update>> getUpdates(@PathParam("token") String token, Tg.GetUpdates request);
}
