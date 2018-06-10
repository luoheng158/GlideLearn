package com.glidedemo;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class LoadingInterceptor implements Interceptor {

    static final Map<String, ProgressListener> mListenerMaps = new HashMap<>();


    public static void addListener(String url, ProgressListener listener) {
        mListenerMaps.put(url, listener);
    }

    public static void removeListener(String url) {
        mListenerMaps.remove(url);
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        Response response = chain.proceed(request);
        String url = request.url().toString();
        ResponseBody body = response.body();
        Response newResponse = response.newBuilder().body(new NewResponseBody(url, body)).build();
        return newResponse;
    }
}
