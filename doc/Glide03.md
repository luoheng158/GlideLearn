## Glide源码分析（三），图片下载流程
这篇文章我们来分析具体的下载图片流程，涉及到的一些控制流程和下载技术。在[Glide源码分析（一），基本加载代码流程](Glide01.md)中，我们分析到，最终
真正开始下载的代码是在SingleRequest#onSizeReady的方法中，下面我们详细分析这个过程。
### 1.SingleRequest#onSizeReady
```
  @Override
    public void onSizeReady(int width, int height) {
	    stateVerifier.throwIfRecycled();
		    if (IS_VERBOSE_LOGGABLE) {
			      logV("Got onSizeReady in " + LogTime.getElapsedMillis(startTime));
				      }
					      if (status != Status.WAITING_FOR_SIZE) {
						        return;
								    }
									    status = Status.RUNNING;

										    float sizeMultiplier = requestOptions.getSizeMultiplier();
											    this.width = maybeApplySizeMultiplier(width, sizeMultiplier);
												    this.height = maybeApplySizeMultiplier(height, sizeMultiplier);

													    if (IS_VERBOSE_LOGGABLE) {
														      logV("finished setup for calling load in " + LogTime.getElapsedMillis(startTime));
															      }
																      loadStatus = engine.load(
																	          glideContext,
																			          model,
																					          requestOptions.getSignature(),
																							          this.width,
																									          this.height,
																											          requestOptions.getResourceClass(),
																													          transcodeClass,
																															          priority,
																																	          requestOptions.getDiskCacheStrategy(),
																																			          requestOptions.getTransformations(),
																																					          requestOptions.isTransformationRequired(),
																																							          requestOptions.isScaleOnlyOrNoTransform(),
																																									          requestOptions.getOptions(),
																																											          requestOptions.isMemoryCacheable(),
																																													          requestOptions.getUseUnlimitedSourceGeneratorsPool(),
																																															          requestOptions.getUseAnimationPool(),
																																																	          requestOptions.getOnlyRetrieveFromCache(),
																																																			          this);

																																																					      // This is a hack that's only useful for testing right now where loads complete synchronously
																																																						      // even though under any executor running on any thread but the main thread, the load would
																																																							      // have completed asynchronously.
																																																								      if (status != Status.RUNNING) {
																																																									        loadStatus = null;
																																																											    }
																																																												    if (IS_VERBOSE_LOGGABLE) {
																																																													      logV("finished onSizeReady in " + LogTime.getElapsedMillis(startTime));
																																																														      }
																																																															    }

```
