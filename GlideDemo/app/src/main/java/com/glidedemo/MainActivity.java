package com.glidedemo;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.manager.RequestManagerRetriever;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ImageView imageView = findViewById(R.id.iv_image);
        Glide.with(this)
                .load("https://p.upyun.com/docs/cloud/demo.jpg")
                .into(imageView);

        RequestManagerRetriever r;
        r.get
        /**
         RequestBuilder
         RequestBuilder 是Glide中请求的骨架，负责携带请求的url和你的设置项来开始一个新的加载过程。
         你想加载的资源类型(Bitmap, Drawable, 或其他)
         你要加载的资源地址(url/model)
         你想最终加载到的View
         任何你想应用的（一个或多个）RequestOption 对象
         任何你想应用的（一个或多个）TransitionOption 对象
         任何你想加载的缩略图 thumbnail()
         */

    }
}
