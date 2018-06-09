package com.glidedemo;

import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        final ImageView imageView = findViewById(R.id.iv_image);

        final ImageView imageView2 = findViewById(R.id.iv_image2);
        findViewById(R.id.load).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Glide.with(MainActivity.this)
                        .load("https://p.upyun.com/docs/cloud/demo.jpg")
                        .apply(new RequestOptions().diskCacheStrategy(DiskCacheStrategy.ALL))
                        .into(imageView);
            }
        });

        findViewById(R.id.load2).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Glide.with(MainActivity.this)
                        .load("https://p.upyun.com/docs/cloud/demo.jpg")
                        .into(imageView2);
            }
        });

        findViewById(R.id.load3).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
                startActivity(new Intent(MainActivity.this, SecondActivity.class));
            }
        });


        findViewById(R.id.load4).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ViewParent viewParent = imageView.getParent();
                ((ViewGroup) viewParent).removeView(imageView);
            }
        });

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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d("DebugAndroid", "OnDestroy....");
    }
}
