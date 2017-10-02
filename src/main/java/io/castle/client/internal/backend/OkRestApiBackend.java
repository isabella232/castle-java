package io.castle.client.internal.backend;

import com.google.gson.*;
import io.castle.client.Castle;
import io.castle.client.internal.config.CastleConfiguration;
import io.castle.client.internal.json.CastleGsonModel;
import io.castle.client.internal.utils.VerdictBuilder;
import io.castle.client.internal.utils.VerdictTransportModel;
import io.castle.client.model.AsyncCallbackHandler;
import io.castle.client.model.CastleRuntimeException;
import io.castle.client.model.Review;
import io.castle.client.model.Verdict;
import okhttp3.*;

import java.io.IOException;

public class OkRestApiBackend implements RestApi {

    private final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private final OkHttpClient client;
    private final CastleGsonModel model;
    private final CastleConfiguration configuration;

    private final HttpUrl track;
    private final HttpUrl authenticate;
    private final HttpUrl identify;
    private final HttpUrl reviewsBase;

    public OkRestApiBackend(OkHttpClient client, CastleGsonModel model, CastleConfiguration configuration) {
        HttpUrl baseUrl = HttpUrl.parse(configuration.getApiBaseUrl());
        this.client = client;
        this.model = model;
        this.configuration = configuration;
        this.track = baseUrl.resolve("/v1/track");
        this.authenticate = baseUrl.resolve("/v1/authenticate");
        this.reviewsBase = baseUrl.resolve("/v1/reviews/");
        this.identify = baseUrl.resolve("/v1/identify");
    }

    @Override
    public void sendTrackRequest(String event, String userId, String reviewId, JsonElement contextPayload, JsonElement propertiesPayload, JsonElement traitPayload, final AsyncCallbackHandler<Boolean> asyncCallbackHandler) {
        JsonObject json = new JsonObject();
        json.add("name", new JsonPrimitive(event));
        if (userId == null) {
            json.add("user_id", JsonNull.INSTANCE);
        } else {
            json.add("user_id", new JsonPrimitive(userId));
        }
        if (reviewId == null) {
            json.add("review_id", JsonNull.INSTANCE);
        } else {
            json.add("review_id", new JsonPrimitive(reviewId));
        }
        json.add("context", contextPayload);
        if (propertiesPayload != null) {
            json.add("properties", propertiesPayload);
        }
        if (traitPayload != null) {
            json.add("trait", traitPayload);
        }
        String content = json.toString();
        RequestBody body = RequestBody.create(JSON, content);
        Request request = new Request.Builder()
                .url(track)
                .post(body)
                .build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Castle.logger.error("HTTP layer. Error sending track request.", e);
                if (asyncCallbackHandler != null) {
                    asyncCallbackHandler.onException(e);
                }
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (asyncCallbackHandler != null) {
                    asyncCallbackHandler.onResponse(response.isSuccessful());
                }
            }
        });
    }

    private Request buildAuthenticateRequest(String event, String userId, JsonElement contextPayload, JsonElement traitsPayload, JsonElement propertiesPayload) {
        JsonObject json = new JsonObject();
        json.add("name", new JsonPrimitive(event));
        json.add("user_id", new JsonPrimitive(userId));
        json.add("context", contextPayload);
        if (propertiesPayload != null) {
            json.add("properties", propertiesPayload);
        }
        if (traitsPayload != null) {
            json.add("traits", traitsPayload);
        }
        RequestBody body = RequestBody.create(JSON, json.toString());
        return new Request.Builder()
                .url(authenticate)
                .post(body)
                .build();
    }

    @Override
    public Verdict sendAuthenticateSync(String event, final String userId, JsonElement contextPayload, JsonElement propertiesPayload, JsonElement traitsPayload) {
        Request request = buildAuthenticateRequest(event, userId, contextPayload, traitsPayload, propertiesPayload);
        try {
            Response response = client.newCall(request).execute();
            return extractAuthenticationAction(response, userId);
        } catch (IOException e) {
            Castle.logger.error("HTTP layer. Error sending request.", e);
            if (configuration.getAuthenticateFailoverStrategy().isThrowTimeoutException()) {
                throw new CastleRuntimeException(e);
            } else {
                return VerdictBuilder.failover(e.getMessage())
                        .withAction(configuration.getAuthenticateFailoverStrategy().getDefaultAction())
                        .withUserId(userId)
                        .build();
            }
        }
    }

    @Override
    public void sendAuthenticateAsync(String event, final String userId, JsonElement contextPayload, JsonElement propertiesPayload, JsonElement traitsPayload, final AsyncCallbackHandler<Verdict> asyncCallbackHandler) {
        Request request = buildAuthenticateRequest(event, userId, contextPayload, traitsPayload, propertiesPayload);
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (configuration.getAuthenticateFailoverStrategy().isThrowTimeoutException()) {
                    asyncCallbackHandler.onException(new CastleRuntimeException(e));
                } else {
                    asyncCallbackHandler.onResponse(
                            VerdictBuilder.failover(e.getMessage())
                                    .withAction(configuration.getAuthenticateFailoverStrategy().getDefaultAction())
                                    .withUserId(userId)
                                    .build()
                    );
                }
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                asyncCallbackHandler.onResponse(extractAuthenticationAction(response, userId));
            }
        });

    }

    private Verdict extractAuthenticationAction(Response response, String userId) throws IOException {
        String errorReason = response.message();
        if (response.isSuccessful()) {
            String jsonResponse = response.body().string();
            Gson gson = model.getGson();
            VerdictTransportModel transport = gson.fromJson(jsonResponse, VerdictTransportModel.class);
            if (transport.getAction() != null && transport.getUserId() != null) {
                return VerdictBuilder.fromTransport(transport);
            } else {
                errorReason = "Illegal json format";
            }
        }
        if(response.code() >= 500) {
            //Use failover for error backends calls.
            if (configuration.getAuthenticateFailoverStrategy().isThrowTimeoutException()) {
                //No timeout, but response is not correct
                throw new IOException("Illegal castle authenticate response.");
            }
            Verdict verdict = VerdictBuilder.failover(errorReason)
                    .withAction(configuration.getAuthenticateFailoverStrategy().getDefaultAction())
                    .withUserId(userId)
                    .build();
            return verdict;
        }
        // Could not extract Verdict, so fail for client logic space.
        throw new CastleRuntimeException("Verdict extraction failed. Backend response error");
    }

    @Override
    public void sendIdentifyRequest(String userId, JsonObject contextJson, boolean active, JsonElement traitsJson) {
        JsonObject json = new JsonObject();
        json.add("user_id", new JsonPrimitive(userId));
//        json.add("active", new JsonPrimitive(active));
        contextJson.add("active", new JsonPrimitive(active));
        json.add("context", contextJson);
        if (traitsJson != null) {
            json.add("traits", traitsJson);
        }
        RequestBody body = RequestBody.create(JSON, json.toString());
        Request request = new Request.Builder()
                .url(identify)
                .post(body)
                .build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Castle.logger.error("HTTP layer. Error sending request.", e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                Castle.logger.debug("Identify request successful");
            }
        });
    }

    @Override
    public Review sendReviewRequestSync(String reviewId) {
        Request request = createReviewRequest(reviewId);
        try {
            Response response = client.newCall(request).execute();
            return extractReview(response);
        } catch (IOException e) {
            throw new CastleRuntimeException(e);
        }
    }

    @Override
    public void sendReviewRequestAsync(String reviewId, final AsyncCallbackHandler<Review> callbackHandler) {
        Request request = createReviewRequest(reviewId);
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callbackHandler.onException(e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                callbackHandler.onResponse(extractReview(response));
            }
        });
    }

    private Review extractReview(Response response) throws IOException {
        if (response.isSuccessful()) {
            String jsonResponse = response.body().string();
            Gson gson = model.getGson();
            return gson.fromJson(jsonResponse, Review.class);
        }
        throw new IOException("HTTP request failure");
    }

    private Request createReviewRequest(String reviewId) {
        HttpUrl reviewUrl = reviewsBase.resolve(reviewId);
        return new Request.Builder()
                .url(reviewUrl)
                .get()
                .build();
    }
}
