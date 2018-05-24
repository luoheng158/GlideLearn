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
唯一标识一些数据的接口。详细介绍[Key结构](key.md)

- EngineKey
实现了Key接口，用做多路复用负载的内存缓存键

- Resource  
一个包装了特定类型的资源接口，并且能够汇集和重用。详细介绍[Resource结构](resource.md)

- MemoryCache  
内存缓存接口，用于在内存缓存中添加和移除资源，这里的实现类是LruResourceCache，继承了LruCache。存放的是Key和Resource键值对。

- DiskCache  
这里的DiskCache是由InternalCacheDiskCacheFactory创建，其继承自DiskLruCacheFactory，最终DiskCache的实现类是DiskLruCacheWrapper对象。

- ActiveResources  
存放了已经加载到Target上面，并且还未于JVM GC回收的Resource资源。

- ResourceRecycler  
一个回收Resource的辅助类，防止陷入递归，当回收的Resource资源有子资源的时候。

- EngineJob  
通过添加和删除回调以进行加载并在加载完成时通知回调来管理加载的类

- DecodeJob  
负责从缓存数据或原始资源解码资源并应用转换和转码的类。

- Jobs
一个负责缓存EngineJob的管理类，里面存放了Key与EngineJob的Map对象。

有了上面的几把认知之后，我们来看看代码的实现，先分析Engine的构造方法，如果觉得思路很乱，建议先看本文结尾的总结，宏观有一个大致的了解，再来看这些过程。
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
options:Options类也是一个实现了Key的接口，与ObjectKey略微不同的是它内部的数据结构是ArrayMap。此时ArrayMap存放的是Option -> com.bumptech.glide.load.resource.bitmap.DownsampleStrategy$FitCenter。Option中的成员变量key为'com.bumptech.glide.load.resource.bitmap.Downsampler.DownsampleStrategy'。  
由上述这些对象共同构造一个EngineKey的对象，它是一个仅在内存中使用的缓存key。实现了equals与hashCode方法。实现如下：

```
  @Override
  public boolean equals(Object o) {
    if (o instanceof EngineKey) {
      EngineKey other = (EngineKey) o;
      return model.equals(other.model)
          && signature.equals(other.signature)
          && height == other.height
          && width == other.width
          && transformations.equals(other.transformations)
          && resourceClass.equals(other.resourceClass)
          && transcodeClass.equals(other.transcodeClass)
          && options.equals(other.options);
    }
    return false;
  }

  @Override
  public int hashCode() {
    if (hashCode == 0) {
      hashCode = model.hashCode();
      hashCode = 31 * hashCode + signature.hashCode();
      hashCode = 31 * hashCode + width;
      hashCode = 31 * hashCode + height;
      hashCode = 31 * hashCode + transformations.hashCode();
      hashCode = 31 * hashCode + resourceClass.hashCode();
      hashCode = 31 * hashCode + transcodeClass.hashCode();
      hashCode = 31 * hashCode + options.hashCode();
    }
    return hashCode;
  }
```
可以看到，由上面各个部分，决定了equals和hashCode的结果。如果有多次请求，则可以根据这些属性生成的EngineKey缓存key，若能匹配到，则可以复用这个缓存结果。接下来就是通过isMemoryCacheable和key去读取缓存。在接下来loadFromActiveResources中，如果能够拿到EngineResource，则整个过程结束，直接使用内存缓存即可，下面我们先分析这个方法的实现。
### 3. Engine#loadFromActiveResources
```
  private EngineResource<?> loadFromActiveResources(Key key, boolean isMemoryCacheable) {
    if (!isMemoryCacheable) {
      return null;
    }
    EngineResource<?> active = activeResources.get(key);
    if (active != null) {
      active.acquire();
    }

    return active;
  }
```
这个方法首先是判断isMemoryCacheable是否可用，不可用直接就返回null，在Engine#load方法中，如果发现是null，则会进行其他的策略，比如读取disk或请求网络等等。如果isMemoryCacheable可用，则从activeResources中去查找，我们来看ActiveResources#get实现。

```
 @Nullable
  EngineResource<?> get(Key key) {
    ResourceWeakReference activeRef = activeEngineResources.get(key);
    if (activeRef == null) {
      return null;
    }

    EngineResource<?> active = activeRef.get();
    if (active == null) {
      cleanupActiveReference(activeRef);
    }
    return active;
  }
```
activeEngineResources是一个Map<Key, ResourceWeakReference>对象，而ResourceWeakReference是WeakReference<EngineResource<?>>的一个子类，既然有取值的地方，那么肯定有对activeEngineResources put内容的时候，在该类中，我们发现其activate方法会向activeEngineResources中put内容。什么时候回触发这个方法，带着这个问题我们继续分析，显然，首次加载这里get方法会返回一个null，因此loadFromActiveResources的结果也是一个null。继续回到load方法，此时loadFromActiveResources返回为null，则继续下一步的策略，进入到loadFromCache方法。它也是如此，如果能够取到资源，则本次load结束，否则继续。
### 4. Engine#loadFromCache
```
 private EngineResource<?> loadFromCache(Key key, boolean isMemoryCacheable) {
    if (!isMemoryCacheable) {
      return null;
    }

    EngineResource<?> cached = getEngineResourceFromCache(key);
    if (cached != null) {
      cached.acquire();
      activeResources.activate(key, cached);
    }
    return cached;
  }
```
这个方法中，依旧是对isMemoryCacheable的简单判断，接下来是调用getEngineResourceFromCache方法去读取，这里发现，如果能够取到资源，则activate方法被触发，这里解决了上面什么时候去往activeEngineResources中put内容的疑问，当然并非只有一处。至于cached.acquire这个暂不深究，用到了计数的概念，acquired大于0时候，表明有地方正在使用resource资源，其实这里可以看到，它这个是一种优化的策略，节省了内存资源。细节往往容易影响主线，我们继续分析getEngineResourceFromCache这个的实现，参数也是一个缓存key。

```
private EngineResource<?> getEngineResourceFromCache(Key key) {
    Resource<?> cached = cache.remove(key);

    final EngineResource<?> result;
    if (cached == null) {
      result = null;
    } else if (cached instanceof EngineResource) {
      // Save an object allocation if we've cached an EngineResource (the typical case).
      result = (EngineResource<?>) cached;
    } else {
      result = new EngineResource<>(cached, true /*isMemoryCacheable*/, true /*isRecyclable*/);
    }
    return result;
  }
```
这里的cache实现了MemoryCache接口，是我们的内存缓存对象，具体的实现类是LruResourceCache实现了LruCache<Key, Resource<?>>，而在LruCache中，维护了一个LinkedHashMap<Key, Resource<?> cache对象，显然有对cache增删查询的操作。这里我们后续分析对cache put的操作。这里，显然cache.remove(key)会返回一个null对象，因此整个方法返回值也是null，继续跟进load代码。

```
    EngineJob<?> current = jobs.get(key, onlyRetrieveFromCache);
    if (current != null) {
      current.addCallback(cb);
      if (VERBOSE_IS_LOGGABLE) {
        logWithTimeAndKey("Added to existing load", startTime, key);
      }
      return new LoadStatus(cb, current);
    }
```
由于loadFromCache返回依然为空，接下来就是从jobs中去寻找，是否存在了一个EngineJob，它是一个通过添加和删除回调以进行加载并在加载完成时通知回调来管理加载的类。如果存在，则可以复用此次的EngineJob，一个EngineJob和ResourceCallback是一个一对多的关系，addCallback方法的实现如下：

```
  void addCallback(ResourceCallback cb) {
    Util.assertMainThread();
    stateVerifier.throwIfRecycled();
    if (hasResource) {
      cb.onResourceReady(engineResource, dataSource);
    } else if (hasLoadFailed) {
      cb.onLoadFailed(exception);
    } else {
      cbs.add(cb);
    }
  }
```
这段代码很清晰，如果已经有资源了，则直接返回，不需要再去请求，大大得到复用了。如果失败，则上报异常信息，否则，则会添加到cbs变量中，说明此时正在加载，会在之后的加载成功或失败中，触发cbs遍历去回调各个Callback。  
首次加载中，显然jobs中拿到的EngineJob也是空，因此进一步分析load，也就是真正去请求了。
### 5. Enging#load
```
public <R> LoadStatus load(...{
	...
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
真正去请求代码也很简单，主要涉及到EngineJob与DecodeJob，DecodeJob是一个负责从缓存数据或原始资源解码资源并应用转换和转码的类，它实现了Runnable接口，是真正加载线程的入口。然后是将engineJob缓存至jobs变量中，最后在EngineJob的start方法中请求线程池去执行本次任务，至此，加载就已经被触发，后面我们继续分析加载的核心逻辑DecodeJob的实现。总的来说，加载分为了以下几个过程：  

1. SingleRequest#onSizeReady方法根据前面RequestBuilder设置的参数，请求Engine#load方法
2. Engine#load方法中，根据相关参数，组装成一个EngineKey，用于标识此次请求的缓存key，首先以这个key去从当前还处理激活状态的Resource资源中去寻找，若查找成功，则返回；否则，进入下一阶段。
3. 若从激活状态的Resource资源查找失败，则进一步去MemoryCache中去查找，若查找成功，则返回；否则，进入下一阶段。
4. 若从MemoryCache中查找失败，则再从jobs中去看是否存在一个已经加载完成或正在加载的EngineJob。若找到，则将回调设置到EngineJob以便接收加载成功或失败的通知；否则，进入下一阶段。
5. 若没有查找到EngineJob，则创建一个EngineJob以及DecodeJob，同时加入到jobs缓存之中，并最终调用EngineJob#start方法，触发加载线程执行真正的加载，从远端获取或者是磁盘获取等。
