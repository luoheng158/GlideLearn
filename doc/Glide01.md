## Glide源码分析（一），基本加载代码流程
所有分析均是基于glide tag 4.7.1的代码
```
       Glide.with(this)
                .load("https://p.upyun.com/docs/cloud/demo.jpg")
                .into(imageView);
```
这应该是最简单的加载代码了  
一步步来，看代码其实很讲究耐心，下面来分析这段比较优雅的代码，其实在Glide源码中，运用到了大量的类的设计，首先后面涉及的我会慢慢介绍到。


，就用到了外观模式，单列模式等，当然还有简单的工厂方法模式，比如这个with方法，如下：

![image](../img/icon_glide_with.png)
Glide#with方法有六个重载的形式，但是第一部分都是调用Glide#getRetriever获取一个RequestManagerRetriever对象，进而调用RequestManagerRetriever#get方法最终创建一个RequestManager对象。下面一个个来进行分析。
###1. Glide#with

```
  @NonNull
  public static RequestManager with(@NonNull FragmentActivity activity) {
    return getRetriever(activity).get(activity);
  }
```
还有其他的重载形式，其实第一部分都是一样，都是通过getRetriever方法去获取一个RequestManagerRetriever对象。
###2. Glide#getRetriever 

```
  @NonNull
  private static RequestManagerRetriever getRetriever(@Nullable Context context) {
    // Context could be null for other reasons (ie the user passes in null), but in practice it will
    // only occur due to errors with the Fragment lifecycle.
    Preconditions.checkNotNull(
        context,
        "You cannot start a load on a not yet attached View or a Fragment where getActivity() "
            + "returns null (which usually occurs when getActivity() is called before the Fragment "
            + "is attached or after the Fragment is destroyed).");
    return Glide.get(context).getRequestManagerRetriever();
  }
```