## DecodeHelper类相关方法分析
DecodeHelper类中，调用的方法，涉及到的东西比较多，最主要的是包括管理组件注册以扩展或替换Glide的默认加载，解码和编码逻辑的Registry类。在Glide类的构造方法中，如下：

```
 Glide(
      @NonNull Context context,
      @NonNull Engine engine,
      @NonNull MemoryCache memoryCache,
      @NonNull BitmapPool bitmapPool,
      @NonNull ArrayPool arrayPool,
      @NonNull RequestManagerRetriever requestManagerRetriever,
      @NonNull ConnectivityMonitorFactory connectivityMonitorFactory,
      int logLevel,
      @NonNull RequestOptions defaultRequestOptions,
      @NonNull Map<Class<?>, TransitionOptions<?, ?>> defaultTransitionOptions) {
      
       ....
       
       registry
        .append(ByteBuffer.class, new ByteBufferEncoder())
        .append(InputStream.class, new StreamEncoder(arrayPool))
        /* Bitmaps */
   ....
   		.append(int.class, Uri.class, resourceLoaderUriFactory)
        .append(String.class, InputStream.class, new DataUrlLoader.StreamFactory<String>())
        .append(Uri.class, InputStream.class, new DataUrlLoader.StreamFactory<Uri>())
        .append(String.class, InputStream.class, new StringLoader.StreamFactory())
        .append(String.class, ParcelFileDescriptor.class, new StringLoader.FileDescriptorFactory())
        .append(
            String.class, AssetFileDescriptor.class, new StringLoader.AssetFileDescriptorFactory())
        .append(Uri.class, InputStream.class, new HttpUriLoader.Factory())
        .append(Uri.class, InputStream.class, new AssetUriLoader.StreamFactory(context.getAssets()))
        .append(
            Uri.class,
            ParcelFileDescriptor.class,
            new AssetUriLoader.FileDescriptorFactory(context.getAssets()))
        .append(Uri.class, InputStream.class, new MediaStoreImageThumbLoader.Factory(context))
        .append(Uri.class, InputStream.class, new MediaStoreVideoThumbLoader.Factory(context))
        .append(
            Uri.class,
            InputStream.class,
            new UriLoader.StreamFactory(contentResolver))

         ....
```
在这里，注册了一系列的信息，我们这里着手关注String.class这块的ModelLoader对象。一般Glide加载一个url字符串的时候，就是通过String.class取对应的ModelLoader对象。

```
    	.append(String.class, InputStream.class, new DataUrlLoader.StreamFactory<String>())
        .append(String.class, InputStream.class, new StringLoader.StreamFactory())
        .append(String.class, ParcelFileDescriptor.class, new StringLoader.FileDescriptorFactory())
        .append(String.class, AssetFileDescriptor.class, new StringLoader.AssetFileDescriptorFactory())
```
最终就是往MultiModelLoaderFactory的成员变量entries加入了四条数据，Entry的结构如下：

```
  private static class Entry<Model, Data> {
    private final Class<Model> modelClass;
    @Synthetic final Class<Data> dataClass;
    @Synthetic final ModelLoaderFactory<? extends Model, ? extends Data> factory;
    
  }
```
暂且先有点这个印象，注册的过程不是我们要重点关注的，Registry不过只是一种构造ModelLoader的实现而已，下面我们先分析DecodeHelper的getLoadData的实现。

```
  List<LoadData<?>> getLoadData() {
    if (!isLoadDataSet) {
      isLoadDataSet = true;
      loadData.clear();
      List<ModelLoader<Object, ?>> modelLoaders = glideContext.getRegistry().getModelLoaders(model);
      //noinspection ForLoopReplaceableByForEach to improve perf
      for (int i = 0, size = modelLoaders.size(); i < size; i++) {
        ModelLoader<Object, ?> modelLoader = modelLoaders.get(i);
        LoadData<?> current =
            modelLoader.buildLoadData(model, width, height, options);
        if (current != null) {
          loadData.add(current);
        }
      }
    }
    return loadData;
  }
```
这里的model是一个Object对象，对于我们的简单url请求来说，它就是一个String类对象。通过代码追踪，getModelLoaders方法，会返回一个ModelLoader的List，它的实现如下：

```
  public <Model> List<ModelLoader<Model, ?>> getModelLoaders(@NonNull Model model) {
    List<ModelLoader<Model, ?>> result = modelLoaderRegistry.getModelLoaders(model);
    if (result.isEmpty()) {
      throw new NoModelLoaderAvailableException(model);
    }
    return result;
  }
```
继续跟进ModelLoaderRegistry的getModelLoaders,

```
 public synchronized <A> List<ModelLoader<A, ?>> getModelLoaders(@NonNull A model) {
    List<ModelLoader<A, ?>> modelLoaders = getModelLoadersForClass(getClass(model));
    int size = modelLoaders.size();
    List<ModelLoader<A, ?>> filteredLoaders = new ArrayList<>(size);
    //noinspection ForLoopReplaceableByForEach to improve perf
    for (int i = 0; i < size; i++) {
      ModelLoader<A, ?> loader = modelLoaders.get(i);
      if (loader.handles(model)) {
        filteredLoaders.add(loader);
      }
    }
    return filteredLoaders;
  }
```
先是调用取model的class类型，然后通过getModelLoadersForClass去获取前面注册进去的loaders信息。获取到之后，通过ModelLoader的handles方法进行一次过滤。最终返回，我们继续分析getModelLoadersForClass。

```
  private <A> List<ModelLoader<A, ?>> getModelLoadersForClass(@NonNull Class<A> modelClass) {
    List<ModelLoader<A, ?>> loaders = cache.get(modelClass);
    if (loaders == null) {
      loaders = Collections.unmodifiableList(multiModelLoaderFactory.build(modelClass));
      cache.put(modelClass, loaders);
    }
    return loaders;
  }
```
可以看到，它是由multiModelLoaderFactory.build方法返回值决定的。下面继续分析这个方法。

```
 synchronized <Model> List<ModelLoader<Model, ?>> build(@NonNull Class<Model> modelClass) {
    try {
      List<ModelLoader<Model, ?>> loaders = new ArrayList<>();
      for (Entry<?, ?> entry : entries) {
        // Avoid stack overflow recursively creating model loaders by only creating loaders in
        // recursive requests if they haven't been created earlier in the chain. For example:
        // A Uri loader may translate to another model, which in turn may translate back to a Uri.
        // The original Uri loader won't be provided to the intermediate model loader, although
        // other Uri loaders will be.
        if (alreadyUsedEntries.contains(entry)) {
          continue;
        }
        if (entry.handles(modelClass)) {
          alreadyUsedEntries.add(entry);
          loaders.add(this.<Model, Object>build(entry));
          alreadyUsedEntries.remove(entry);
        }
      }
      return loaders;
    } catch (Throwable t) {
      alreadyUsedEntries.clear();
      throw t;
    }
  }
```
Entry我们上面已经有所介绍，和String有关的目前有四个，重点就是entry.handles方法的返回值，决定其是否能加入到loaders中，而在这个方法中，实现就是看是否和modelClass能够匹配，所以此时和String.class能匹配的就是上面已经列出的。

```
    	.append(String.class, InputStream.class, new DataUrlLoader.StreamFactory<String>())
        .append(String.class, InputStream.class, new StringLoader.StreamFactory())
        .append(String.class, ParcelFileDescriptor.class, new StringLoader.FileDescriptorFactory())
        .append(String.class, AssetFileDescriptor.class, new StringLoader.AssetFileDescriptorFactory())
```
接下来会调用build方法传入entry获取一个ModelLoader对象，build实现如下：

```
  private <Model, Data> ModelLoader<Model, Data> build(@NonNull Entry<?, ?> entry) {
    return (ModelLoader<Model, Data>) Preconditions.checkNotNull(entry.factory.build(this));
  }
```
这里就是调用entry对应的factory对象去构建ModelLoader。这里四个工厂分别是DataUrlLoader.StreamFactory、StringLoader.StreamFactory、StringLoader.FileDescriptorFactory与StringLoader.AssetFileDescriptorFactory。分别构造了DataUrlLoader、StringLoader、StringLoader、StringLoader四个loader对象。  
对于一个url，这里我们的是:"https://p.upyun.com/docs/cloud/demo.jpg",ModelLoader中有一个handles方法，表明此类型是否可以被自己处理。  
DataUrlLoader的实现如下：

```
 public boolean handles(@NonNull Model model) {
    // We expect Model to be a Uri or a String, both of which implement toString() efficiently. We
    // should reconsider this implementation before adding any new Model types.
    return model.toString().startsWith(DATA_SCHEME_IMAGE);
  }
```
其中DATA_SCHEME_IMAGE为"data:image"，显然此时不能处理我们的url。
StringLoader的实现如下：

```
  public boolean handles(@NonNull String model) {
    return true;
  }

```
显然此时，是可以处理这个url对象的。 三个Factory的build方法的实现如下：

```
 public static class StreamFactory implements ModelLoaderFactory<String, InputStream> {

    @NonNull
    @Override
    public ModelLoader<String, InputStream> build(MultiModelLoaderFactory multiFactory) {
      return new StringLoader<>(multiFactory.build(Uri.class, InputStream.class));
    }

  }

  /**
   * Factory for loading {@link ParcelFileDescriptor}s from Strings.
   */
  public static class FileDescriptorFactory
      implements ModelLoaderFactory<String, ParcelFileDescriptor> {

    @NonNull
    @Override
    public ModelLoader<String, ParcelFileDescriptor> build(MultiModelLoaderFactory multiFactory) {
      return new StringLoader<>(multiFactory.build(Uri.class, ParcelFileDescriptor.class));
    }


  }

  /**
   * Loads {@link AssetFileDescriptor}s from Strings.
   */
  public static final class AssetFileDescriptorFactory
      implements ModelLoaderFactory<String, AssetFileDescriptor> {

    @Override
    public ModelLoader<String, AssetFileDescriptor> build(MultiModelLoaderFactory multiFactory) {
      return new StringLoader<>(multiFactory.build(Uri.class, AssetFileDescriptor.class));
    }

  }
``` 
这块相对有点复杂，在StringLoader中有一个成员变量uriLoader，它也是由entry传入的MultiModelLoaderFactory的build方法继续创建的。这里我们可以看到有一个递归的过程，其实也很好理解，虽然三个都是StringLoader，但内部的成员变量uriLoader不一样。分别是三对：  

1. Uri.class, InputStream.class
2. Uri.class, ParcelFileDescriptor.class
3. Uri.class, AssetFileDescriptor.class
这里其实和之前的逻辑类似，也是从glide中查表，继续看关于这三对的注册情况。仍然是在Glide构造方法中注册的。

- Uri.class, InputStream.class

```
 .append(Uri.class, InputStream.class, new DataUrlLoader.StreamFactory<Uri>())
 .append(Uri.class, InputStream.class, new HttpUriLoader.Factory())
 .append(Uri.class, InputStream.class, new AssetUriLoader.StreamFactory(context.getAssets()))
 .append(Uri.class, InputStream.class, new MediaStoreImageThumbLoader.Factory(context))
 .append(Uri.class, InputStream.class, new MediaStoreVideoThumbLoader.Factory(context))
 .append(Uri.class, InputStream.class, new UriLoader.StreamFactory(contentResolver))
 .append(Uri.class, InputStream.class, new UrlUriLoader.StreamFactory())
```
- Uri.class, ParcelFileDescriptor.class
  
```
  .append(Uri.class, ParcelFileDescriptor.class, new AssetUriLoader.FileDescriptorFactory(context.getAssets()))  
  .append(Uri.class, ParcelFileDescriptor.class, new UriLoader.FileDescriptorFactory(contentResolver))
```
- Uri.class, AssetFileDescriptor.class  

```
   .append(Uri.class,AssetFileDescriptor.class,
            new UriLoader.AssetFileDescriptorFactory(contentResolver))
```  

因此，上面三个StringLoader中，成员变量uriLoader分别为:  

- Uri.class, InputStream.class 
  > MultiModelLoader：  
  [DataUrlLoader、HttpUriLoader、AssetUriLoader、MediaStoreImageThumbLoader、MediaStoreVideoThumbLoader、UriLoader、UrlUriLoader]

- Uri.class, ParcelFileDescriptor.class -> MultiModelLoader 
 > MultiModelLoader：  
  [AssetUriLoader、UriLoader] 
- Uri.class, AssetFileDescriptor.class
 > UriLoader 
  
ModelLoader最重要的功能就是通过buildLoadData创建相应的LoadData对象。  
MultiModelLoader实现如下：

```
 public LoadData<Data> buildLoadData(@NonNull Model model, int width, int height,
      @NonNull Options options) {
    Key sourceKey = null;
    int size = modelLoaders.size();
    List<DataFetcher<Data>> fetchers = new ArrayList<>(size);
    //noinspection ForLoopReplaceableByForEach to improve perf
    for (int i = 0; i < size; i++) {
      ModelLoader<Model, Data> modelLoader = modelLoaders.get(i);
      if (modelLoader.handles(model)) {
        LoadData<Data> loadData = modelLoader.buildLoadData(model, width, height, options);
        if (loadData != null) {
          sourceKey = loadData.sourceKey;
          fetchers.add(loadData.fetcher);
        }
      }
    }
    return !fetchers.isEmpty() && sourceKey != null
        ? new LoadData<>(sourceKey, new MultiFetcher<>(fetchers, exceptionListPool)) : null;
  }
```
对于我们的三种情况来讲，考虑到handles的过滤，model是"https://p.upyun.com/docs/cloud/demo.jpg"，因此最终创建的LoadData的fetchers信息如下：

- Uri.class, InputStream.class 
  > LoadData: [{GlideUrl, HttpUrlFetcher},{GlideUrl, HttpUrlFetcher}]
   
- Uri.class, ParcelFileDescriptor.class -> MultiModelLoader 
 > LoadData: [] 
 
- Uri.class, AssetFileDescriptor.class
 > LoadData:[{ObjectKey, AssetFileDescriptorLocalUriFetcher}]
  

至此返回的三个StringLoader以及最终创建的LoadData也就理清楚了。下面回到DecodeHelper的getLoadData中：

```
  List<LoadData<?>> getLoadData() {
    if (!isLoadDataSet) {
      isLoadDataSet = true;
      loadData.clear();
      List<ModelLoader<Object, ?>> modelLoaders = glideContext.getRegistry().getModelLoaders(model);
      //noinspection ForLoopReplaceableByForEach to improve perf
      for (int i = 0, size = modelLoaders.size(); i < size; i++) {
        ModelLoader<Object, ?> modelLoader = modelLoaders.get(i);
        LoadData<?> current =
            modelLoader.buildLoadData(model, width, height, options);
        if (current != null) {
          loadData.add(current);
        }
      }
    }
    return loadData;
  }
```
这里就是调用前面创建的三个StringLoader，而后调用其buildLoadData方法，获取到相应的LoadData对象，这里我们可以知道，最终的loadData这个列表如下： 
> [
> 
> {LoadData: [{GlideUrl, HttpUrlFetcher},{GlideUrl, HttpUrlFetcher}]},  
>  {LoadData:[{ObjectKey, AssetFileDescriptorLocalUriFetcher}]}
> 
> ]
   
至此，再看getCacheKeys的实现也就非常简单了。

```
List<Key> getCacheKeys() {
    if (!isCacheKeysSet) {
      isCacheKeysSet = true;
      cacheKeys.clear();
      List<LoadData<?>> loadData = getLoadData();
      //noinspection ForLoopReplaceableByForEach to improve perf
      for (int i = 0, size = loadData.size(); i < size; i++) {
        LoadData<?> data = loadData.get(i);
        if (!cacheKeys.contains(data.sourceKey)) {
          cacheKeys.add(data.sourceKey);
        }
        for (int j = 0; j < data.alternateKeys.size(); j++) {
          if (!cacheKeys.contains(data.alternateKeys.get(j))) {
            cacheKeys.add(data.alternateKeys.get(j));
          }
        }
      }
    }
    return cacheKeys;
  }
```
因此此时的cacheKeys中的内容就是：
> [GlideUrl,ObjectKey]

这里我们分析了DecodeHelper类中，最复杂的获取loadData的方法，没有在注册这块花太大的精力，后面的分析中，我们会看到这个辅助类的使用。
