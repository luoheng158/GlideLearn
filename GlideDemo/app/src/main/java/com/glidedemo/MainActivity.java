package com.glidedemo;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.load.engine.cache.DiskLruCacheFactory;
import com.bumptech.glide.load.engine.cache.LruResourceCache;
import com.bumptech.glide.manager.DefaultConnectivityMonitorFactory;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        GlideBuilder glideBuilder = new GlideBuilder();
        glideBuilder.setMemoryCache(new LruResourceCache(1024));
        glideBuilder.setDiskCache(new DiskLruCacheFactory("", 1024));
        glideBuilder.setConnectivityMonitorFactory(new DefaultConnectivityMonitorFactory());

        GlideApp.with(this);
        Glide.with(this);

    }
}
