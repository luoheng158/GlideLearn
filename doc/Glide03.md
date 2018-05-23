## Glide源码分析（三），Engine加载资源过程
通过前面的分析，我们知道真正去加载数据是在SingleRequest#onSizeReady方法中被触发，这个里面是调用了Engine#load方法，看到这个方法，我们大致可以猜到此时便开始去真正加载数据了，从缓存中读取或者是从网络获取等等。在开始之前，我们先简单了解一下Engine类中涉及到的一些类。
仍然以最简单的load方式为例子

```
   Glide.with(this)
                .load("https://p.upyun.com/docs/cloud/demo.jpg")
                .into(imageView);
```
### 关键类
- Key  
唯一标识一些数据的接口。

- EngineKey  
实现了Key接口，用做多路复用负载的内存缓存键

- Resource  
一个包装了特定类型的资源接口，并且能够汇集和重用。

- MemoryCache  
内存缓存接口，用于在内存缓存中添加和移除资源，这里的实现类是LruResourceCache，继承了LruCache。存放的是Key和Resource键值对。

- DiskCache  
这里的DiskCache是由InternalCacheDiskCacheFactory创建，其继承自DiskLruCacheFactory，最终DiskCache的实现类是DiskLruCacheWrapper对象。

- ActiveResources  
？？？？？

- ResourceRecycler  
一个回收Resource的辅助类，防止陷入递归，当回收的Resource资源有子资源的时候。

- EngineJob

- DecodeJob

- Jobs


有了上面的几把认知之后，我们来看看代码的实现，先分析Engine的构造方法。
### 1. Engine#Engine
```
 public Engine(
      MemoryCache memoryCache,
      DiskCache.Factory diskCacheFactory,
      GlideExecutor diskCacheExecutor,
      GlideExecutor sourceExecutor,
      GlideExecutor sourceUnlimitedExecutor,
      GlideExecutor animationExecutor,
      boolean isActiveResourceRetentionAllowed) {
    this(
        memoryCache,
        diskCacheFactory,
        diskCacheExecutor,
        sourceExecutor,
        sourceUnlimitedExecutor,
        animationExecutor,
        /*jobs=*/ null,
        /*keyFactory=*/ null,
        /*activeResources=*/ null,
        /*engineJobFactory=*/ null,
        /*decodeJobFactory=*/ null,
        /*resourceRecycler=*/ null,
        isActiveResourceRetentionAllowed);
  }

  @VisibleForTesting
  Engine(MemoryCache cache,
      DiskCache.Factory diskCacheFactory,
      GlideExecutor diskCacheExecutor,
      GlideExecutor sourceExecutor,
      GlideExecutor sourceUnlimitedExecutor,
      GlideExecutor animationExecutor,
      Jobs jobs,
      EngineKeyFactory keyFactory,
      ActiveResources activeResources,
      EngineJobFactory engineJobFactory,
      DecodeJobFactory decodeJobFactory,
      ResourceRecycler resourceRecycler,
      boolean isActiveResourceRetentionAllowed) {
    this.cache = cache;
    this.diskCacheProvider = new LazyDiskCacheProvider(diskCacheFactory);

    if (activeResources == null) {
      activeResources = new ActiveResources(isActiveResourceRetentionAllowed);
    }
    this.activeResources = activeResources;
    activeResources.setListener(this);

    if (keyFactory == null) {
      keyFactory = new EngineKeyFactory();
    }
    this.keyFactory = keyFactory;

    if (jobs == null) {
      jobs = new Jobs();
    }
    this.jobs = jobs;

    if (engineJobFactory == null) {
      engineJobFactory =
          new EngineJobFactory(
              diskCacheExecutor, sourceExecutor, sourceUnlimitedExecutor, animationExecutor, this);
    }
    this.engineJobFactory = engineJobFactory;

    if (decodeJobFactory == null) {
      decodeJobFactory = new DecodeJobFactory(diskCacheProvider);
    }
    this.decodeJobFactory = decodeJobFactory;

    if (resourceRecycler == null) {
      resourceRecycler = new ResourceRecycler();
    }
    this.resourceRecycler = resourceRecycler;

    cache.setResourceRemovedListener(this);
  }
```
构造方法中，初始化了部分成员变量和一些构造类的工厂，还包括一些辅助的Resource回收的类ResourceRecycler，其中比较重要的就是Cache、KeyFactory、EngineJob和DecodeJob。下面会看到在load方法中，如何去使用的。
### 2.Engine#load
```
public <R> LoadStatus load(
      GlideContext glideContext,
      Object model,
      Key signature,
      int width,
      int height,
      Class<?> resourceClass,
      Class<R> transcodeClass,
      Priority priority,
      DiskCacheStrategy diskCacheStrategy,
      Map<Class<?>, Transformation<?>> transformations,
      boolean isTransformationRequired,
      boolean isScaleOnlyOrNoTransform,
      Options options,
      boolean isMemoryCacheable,
      boolean useUnlimitedSourceExecutorPool,
      boolean useAnimationPool,
      boolean onlyRetrieveFromCache,
      ResourceCallback cb) {
    Util.assertMainThread();
    long startTime = VERBOSE_IS_LOGGABLE ? LogTime.getLogTime() : 0;

    EngineKey key = keyFactory.buildKey(model, signature, width, height, transformations,
        resourceClass, transcodeClass, options);

    EngineResource<?> active = loadFromActiveResources(key, isMemoryCacheable);
    if (active != null) {
      cb.onResourceReady(active, DataSource.MEMORY_CACHE);
      if (VERBOSE_IS_LOGGABLE) {
        logWithTimeAndKey("Loaded resource from active resources", startTime, key);
      }
      return null;
    }

    EngineResource<?> cached = loadFromCache(key, isMemoryCacheable);
    if (cached != null) {
      cb.onResourceReady(cached, DataSource.MEMORY_CACHE);
      if (VERBOSE_IS_LOGGABLE) {
        logWithTimeAndKey("Loaded resource from cache", startTime, key);
      }
      return null;
    }

    EngineJob<?> current = jobs.get(key, onlyRetrieveFromCache);
    if (current != null) {
      current.addCallback(cb);
      if (VERBOSE_IS_LOGGABLE) {
        logWithTimeAndKey("Added to existing load", startTime, key);
      }
      return new LoadStatus(cb, current);
    }

    EngineJob<R> engineJob =
        engineJobFactory.build(
            key,
            isMemoryCacheable,
            useUnlimitedSourceExecutorPool,
            useAnimationPool,
            onlyRetrieveFromCache);

    DecodeJob<R> decodeJob =
        decodeJobFactory.build(
            glideContext,
            model,
            key,
            signature,
            width,
            height,
            resourceClass,
            transcodeClass,
            priority,
            diskCacheStrategy,
            transformations,
            isTransformationRequired,
            isScaleOnlyOrNoTransform,
            onlyRetrieveFromCache,
            options,
            engineJob);

    jobs.put(key, engineJob);

    engineJob.addCallback(cb);
    engineJob.start(decodeJob);

    if (VERBOSE_IS_LOGGABLE) {
      logWithTimeAndKey("Started new load", startTime, key);
    }
    return new LoadStatus(cb, engineJob);
  }
```
这段代码第一是通过keyFactory构建一个EngineKey，这个key信息包含了以下几个部分：  
model:此时是图片url  
signature: 额外的数据，能够和memory与disk的缓存key进行再一次混合，当缓存失效时候，可以进行更多的控制。一般情况下默认实现是EmptySignature。  
width/height:加载的尺寸  
transformations: 是一个Map<Class<?>, Transformation<?>>键值对，Transformation也是可以和memory与disk的缓存key进行进一步混合，添加额外的信息。在此时它里面放置了以下信息：
Bitmap.class -> FitCenter  
Drawable.class -> DrawableTransformation  
BitmapDrawable.class -> DrawableTransformation  
GifDrawable.class -> GifDrawableTransformation  
resourceClass: 此时为Object.class. 未知  
transcodeClass: 指定了需要返回的Resource类型   
options:Options{values={Option{key='com.bumptech.glide.load.resource.bitmap.Downsampler.DownsampleStrategy'}=com.bumptech.glide.load.resource.bitmap.DownsampleStrategy$FitCenter@875ebeb}}
