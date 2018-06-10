package com.glidedemo;

import android.util.Log;

import java.io.IOException;

import okhttp3.MediaType;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;
import okio.Source;

public class NewResponseBody extends ResponseBody {


    private BufferedSource bufferedSource;
    private ResponseBody responseBody;
    private ProgressListener listener;

    public NewResponseBody(String url, ResponseBody responseBody) {
        this.responseBody = responseBody;
        listener = LoadingInterceptor.mListenerMaps.get(url);
    }


    @Override
    public MediaType contentType() {
        return responseBody.contentType();
    }

    @Override
    public long contentLength() {
        return responseBody.contentLength();
    }

    @Override
    public BufferedSource source() {
        if (bufferedSource == null) {
            bufferedSource = Okio.buffer(new ProgressSource(responseBody.source()));
        }
        return bufferedSource;
    }

    private class ProgressSource extends ForwardingSource {

        long totalBytesRead = 0;
        int currentProgress;

        public ProgressSource(Source delegate) {
            super(delegate);
        }

        @Override
        public long read(Buffer sink, long byteCount) throws IOException {
            long bytesRead = super.read(sink, byteCount);
            long fullLength = responseBody.contentLength();
            if (bytesRead == -1) {
                totalBytesRead = fullLength;
            } else {
                totalBytesRead += bytesRead;
            }
            int progress = (int) (100f * totalBytesRead / fullLength);
            Log.d("OKHTTP_READ", "download progress is " + progress + ", Thr " + Thread.currentThread());
            if (listener != null && progress != currentProgress) {
                listener.onProgress(progress);
            }
            currentProgress = progress;
            return bytesRead;
        }
    }
}
