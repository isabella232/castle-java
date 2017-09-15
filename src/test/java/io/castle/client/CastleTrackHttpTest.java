package io.castle.client;

import io.castle.client.model.AsyncCallbackHandler;
import io.castle.client.model.AuthenticateAction;
import io.castle.client.model.AuthenticateFailoverStrategy;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.assertj.core.api.Assertions;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import javax.servlet.http.HttpServletRequest;
import java.util.concurrent.atomic.AtomicReference;

public class CastleTrackHttpTest extends AbstractCastleHttpLayerTest {

    public CastleTrackHttpTest() {
        super(new AuthenticateFailoverStrategy(AuthenticateAction.CHALLENGE));
    }

    @Test
    public void trackEndpointWithUserIDTest() throws InterruptedException {
        //given
        server.enqueue(new MockResponse());
        String id = "12345";
        String event = "$login.succeeded";

        // And a mock Request
        HttpServletRequest request = new MockHttpServletRequest();

        // and an track request is made
        sdk.onRequest(request).track(event, id);

        // then
        RecordedRequest recordedRequest = server.takeRequest();
        Assert.assertEquals("{\"name\":\"$login.succeeded\",\"user_id\":\"12345\",\"context\":{\"active\":true,\"ip\":\"127.0.0.1\",\"headers\":{\"REMOTE_ADDR\":\"127.0.0.1\"},\"library\":{\"name\":\"Castle\",\"version\":\"0.6.0-SNAPSHOT\"}}}",
                recordedRequest.getBody().readUtf8());
    }

    @Test
    public void trackEndpointWithUserIDAndPropertiesTest() throws InterruptedException {
        //given
        server.enqueue(new MockResponse());
        String id = "12345";
        String event = "$login.succeeded";

        // And a mock Request
        HttpServletRequest request = new MockHttpServletRequest();

        // And
        CustomAppProperties properties = new CustomAppProperties();
        properties.setA("valueA");
        properties.setB(123456);

        // and an track request is made
        sdk.onRequest(request).track(event, id, properties);

        // then
        RecordedRequest recordedRequest = server.takeRequest();
        Assert.assertEquals("{\"name\":\"$login.succeeded\",\"user_id\":\"12345\",\"context\":{\"active\":true,\"ip\":\"127.0.0.1\",\"headers\":{\"REMOTE_ADDR\":\"127.0.0.1\"},\"library\":{\"name\":\"Castle\",\"version\":\"0.6.0-SNAPSHOT\"}},\"properties\":{\"a\":\"valueA\",\"b\":123456}}",
                recordedRequest.getBody().readUtf8());
    }

    @Test
    public void trackEndpointWithMinimalInformationTest() throws InterruptedException {
        //given
        server.enqueue(new MockResponse());
        String event = "any.valid.event";
        // And a mock Request
        HttpServletRequest request = new MockHttpServletRequest();

        // and an track request is made
        sdk.onRequest(request).track(event);

        // then
        RecordedRequest recordedRequest = server.takeRequest();
        Assert.assertEquals("{\"name\":\"any.valid.event\",\"user_id\":null,\"context\":{\"active\":true,\"ip\":\"127.0.0.1\",\"headers\":{\"REMOTE_ADDR\":\"127.0.0.1\"},\"library\":{\"name\":\"Castle\",\"version\":\"0.6.0-SNAPSHOT\"}}}",
                recordedRequest.getBody().readUtf8());
    }

    @Test
    public void trackEndpointAsyncTest() throws InterruptedException {
        //given
        server.enqueue(new MockResponse().setResponseCode(200));
        String event = "any.valid.event";
        // And a mock Request
        HttpServletRequest request = new MockHttpServletRequest();

        // and an async track request is made
        final AtomicReference<Boolean> result = new AtomicReference<>();
        AsyncCallbackHandler<Boolean> callback = new AsyncCallbackHandler<Boolean>() {
            @Override
            public void onResponse(Boolean response) {
                result.set(response);
            }

            @Override
            public void onException(Exception exception) {
                Assertions.fail("No exception expected here");
            }
        };
        sdk.onRequest(request).track(event, null, null, callback);

        // then the callback response is called with true
        Boolean extracted = waitForValue(result);
        Assertions.assertThat(extracted).isTrue();
    }

    @Test
    public void trackEndpointTimeoutTest() throws InterruptedException {
        // given the backend will timeout
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        String event = "any.valid.event";
        // and a mock Request
        final HttpServletRequest request = new MockHttpServletRequest();

        // and a async handler is prepared
        final AtomicReference<Boolean> result = new AtomicReference<>();
        AsyncCallbackHandler<Boolean> callback = new AsyncCallbackHandler<Boolean>() {
            @Override
            public void onResponse(Boolean response) {
                Assertions.fail("Request should end with an timeout exception and not result");
            }

            @Override
            public void onException(Exception exception) {
                result.set(false);
            }
        };
        // when an track request is made
        sdk.onRequest(request).track(event, null, null, callback);

        // then the track request must be send
        RecordedRequest recordedRequest = server.takeRequest();
        Assert.assertEquals("{\"name\":\"any.valid.event\",\"user_id\":null,\"context\":{\"active\":true,\"ip\":\"127.0.0.1\",\"headers\":{\"REMOTE_ADDR\":\"127.0.0.1\"},\"library\":{\"name\":\"Castle\",\"version\":\"0.6.0-SNAPSHOT\"}}}",
                recordedRequest.getBody().readUtf8());

        // and the onException method must be called
        waitForValueAndVerify(result, Boolean.FALSE);
    }

    @Test
    public void trackEndpointTimeoutAreIgnoreWhenNoCallbackIsProvidedTest() throws InterruptedException {
        // given the backend will timeout
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        String event = "any.valid.event";
        // and a mock Request
        final HttpServletRequest request = new MockHttpServletRequest();

        // when an track request is made
        sdk.onRequest(request).track(event, null, null, null);

        // then the track request must be send
        RecordedRequest recordedRequest = server.takeRequest();
        Assert.assertEquals("{\"name\":\"any.valid.event\",\"user_id\":null,\"context\":{\"active\":true,\"ip\":\"127.0.0.1\",\"headers\":{\"REMOTE_ADDR\":\"127.0.0.1\"},\"library\":{\"name\":\"Castle\",\"version\":\"0.6.0-SNAPSHOT\"}}}",
                recordedRequest.getBody().readUtf8());

        // and no exceptions are thrown in any thread
    }

}