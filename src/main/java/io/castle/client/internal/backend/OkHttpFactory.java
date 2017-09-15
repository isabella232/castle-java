package io.castle.client.internal.backend;

import com.google.common.io.BaseEncoding;
import io.castle.client.internal.config.CastleConfiguration;
import io.castle.client.internal.json.CastleGsonModel;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.logging.HttpLoggingInterceptor;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class OkHttpFactory implements RestApiFactory {

    private final OkHttpClient client;
    private final CastleGsonModel modelInstance;
    private final CastleConfiguration configuration;

    public OkHttpFactory(CastleConfiguration configuration, CastleGsonModel modelInstance) {
        this.configuration = configuration;
        this.modelInstance = modelInstance;
        client = createOkHttpClient();
    }

    private OkHttpClient createOkHttpClient() {
        final String authString = ":" + configuration.getApiSecret();
        final String authStringBase64 = "Basic " + BaseEncoding.base64().encode(authString.getBytes());

        OkHttpClient.Builder builder = new OkHttpClient()
                .newBuilder()
                .connectTimeout(configuration.getTimeout(), TimeUnit.MILLISECONDS)
                .readTimeout(configuration.getTimeout(), TimeUnit.MILLISECONDS)
                .writeTimeout(configuration.getTimeout(), TimeUnit.MILLISECONDS);
        if (configuration.isLogHttpRequests()) {
            HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
            // TODO provide more configurable logging features.
            logging.setLevel(HttpLoggingInterceptor.Level.BODY);
            builder = builder.addInterceptor(logging);
        }

        OkHttpClient client = builder
                .addInterceptor(new Interceptor() {
                    @Override
                    public Response intercept(Chain chain) throws IOException {
                        Request request = chain.request();
                        Request authenticatedRequest = request.newBuilder()
                                .header("Authorization", authStringBase64).build();
                        return chain.proceed(authenticatedRequest);
                    }
                })
                .build();
        return client;
    }

    @Override
    public RestApi buildBackend() {
        return new OkRestApiBackend(client, modelInstance, configuration);
    }
}