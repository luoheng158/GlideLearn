package com.glidedemo;

import android.content.Context;
import android.graphics.Bitmap;
import android.support.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.bumptech.glide.Registry;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.resource.bitmap.Downsampler;
import com.bumptech.glide.module.AppGlideModule;
import com.glidedemo.decoder.MyByteBufferBitmapDecoder;

import java.io.InputStream;
import java.nio.ByteBuffer;

import okhttp3.OkHttpClient;

/**
 * Created by carlos on 08/05/2018.
 */

@GlideModule
public class MyAppGlideModule extends AppGlideModule {

    @Override
    public void registerComponents(@NonNull Context context, @NonNull Glide glide, @NonNull Registry registry) {
        super.registerComponents(context, glide, registry);
        OkHttpClient.Builder builder = new OkHttpClient.Builder();
        builder.addInterceptor(new LoadingInterceptor());
        OkHttpClient okHttpClient = builder.build();


        Downsampler downsampler = new Downsampler(registry.getImageHeaderParsers(),
                context.getResources().getDisplayMetrics(), glide.getBitmapPool(), glide.getArrayPool());
        MyByteBufferBitmapDecoder byteBufferBitmapDecoder = new MyByteBufferBitmapDecoder(downsampler);

        registry.prepend(Registry.BUCKET_BITMAP, ByteBuffer.class, Bitmap.class, byteBufferBitmapDecoder);
        registry.replace(GlideUrl.class, InputStream.class, new OkHttpUrlLoader.Factory(okHttpClient));
    }
}
