package com.glidedemo;

import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.annotation.GlideExtension;
import com.bumptech.glide.annotation.GlideOption;
import com.bumptech.glide.annotation.GlideType;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.bumptech.glide.load.resource.gif.GifDrawable;
import com.bumptech.glide.request.RequestOptions;

@GlideExtension
public class MyAppExtension {

    private static final int MIN_THUMB_SIZE = 100;

    private static final RequestOptions DECODE_TYPE_GIF = RequestOptions.decodeTypeOf(GifDrawable.class).lock();

    private MyAppExtension() {

    }

    @GlideOption
    public static void miniThumb(RequestOptions options) {
        options
                .fitCenter()
                .override(MIN_THUMB_SIZE);
    }


    @GlideType(GifDrawable.class)
    public static void asMyGif(RequestBuilder<GifDrawable> requestBuilder) {
        requestBuilder
                .transition(new DrawableTransitionOptions())
                .apply(DECODE_TYPE_GIF);
    }


    @GlideOption
    public static void miniThumb(RequestOptions options, int size) {
        options
                .fitCenter()
                .override(size);
    }

}
