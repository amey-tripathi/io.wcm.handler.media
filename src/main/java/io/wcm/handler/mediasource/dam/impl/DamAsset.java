/*
 * #%L
 * wcm.io
 * %%
 * Copyright (C) 2014 wcm.io
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package io.wcm.handler.mediasource.dam.impl;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.sling.api.adapter.Adaptable;
import org.apache.sling.api.adapter.SlingAdaptable;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.day.cq.dam.api.DamConstants;

import io.wcm.handler.media.Asset;
import io.wcm.handler.media.CropDimension;
import io.wcm.handler.media.Dimension;
import io.wcm.handler.media.Media;
import io.wcm.handler.media.MediaArgs;
import io.wcm.handler.media.MediaFileType;
import io.wcm.handler.media.Rendition;
import io.wcm.handler.media.UriTemplate;
import io.wcm.handler.media.UriTemplateType;
import io.wcm.handler.media.spi.MediaHandlerConfig;
import io.wcm.handler.mediasource.dam.AssetRendition;
import io.wcm.handler.mediasource.dam.impl.dynamicmedia.DynamicMediaSupportService;
import io.wcm.wcm.commons.util.ToStringStyle;

/**
 * {@link Asset} implementation for DAM assets.
 */
public final class DamAsset extends SlingAdaptable implements Asset {

  private final com.day.cq.dam.api.Asset damAsset;
  private final CropDimension cropDimension;
  private final Integer rotation;
  private final MediaArgs defaultMediaArgs;
  private final DamContext damContext;

  /**
   * @param media Media metadata
   * @param damAsset DAM asset
   * @param mediaHandlerConfig Media handler config
   * @param dynamicMediaSupportService Dynamic media support service
   * @param adaptable Adaptable from current context
   */
  public DamAsset(Media media, com.day.cq.dam.api.Asset damAsset, MediaHandlerConfig mediaHandlerConfig,
      DynamicMediaSupportService dynamicMediaSupportService, Adaptable adaptable) {
    this.damAsset = damAsset;
    this.cropDimension = rescaleCropDimension(damAsset, media.getCropDimension());
    this.rotation = media.getRotation();
    this.defaultMediaArgs = media.getMediaRequest().getMediaArgs();
    this.damContext = new DamContext(damAsset, defaultMediaArgs, mediaHandlerConfig,
        dynamicMediaSupportService, adaptable);
  }

  /**
   * Crop dimension stored in repository is always calucated against the web-enabled rendition of an asset.
   * Rescale the crop-dimension here once to calculate it against the original image, which will be used for the actual
   * cropping.
   * @param asset Asset
   * @param cropDimension Crop dimension from repository/input parameters
   * @return Rescaled crop dimension
   */
  private static @Nullable CropDimension rescaleCropDimension(@NotNull com.day.cq.dam.api.Asset asset, @Nullable CropDimension cropDimension) {
    if (cropDimension == null) {
      return null;
    }
    return WebEnabledRenditionCropping.getCropDimensionForOriginal(asset, cropDimension);
  }

  @Override
  public String getTitle() {
    String title = getPropertyAwareOfArray(DamConstants.DC_TITLE);
    // fallback to asset name if title is empty
    return StringUtils.defaultString(title, damContext.getAsset().getName());
  }

  /**
   * Get string value from properties. If value is an array, get first item of array.
   * It might happen that the adobe xmp lib creates an array, e.g. if the asset file already has a title attribute.
   * @param propertyName Property name
   * @return Single value
   */
  private @Nullable String getPropertyAwareOfArray(@NotNull String propertyName) {
    Object valueObject = damAsset.getMetadataValueFromJcr(propertyName);
    String value = null;
    if (valueObject != null) {
      if (valueObject instanceof Object[]) {
        Object[] valueArray = (Object[])valueObject;
        if (valueArray.length > 0) {
          value = valueArray[0].toString();
        }
      }
      else {
        value = valueObject.toString();
      }
    }
    return StringUtils.defaultIfBlank(value, null);
  }

  @Override
  public String getAltText() {
    if (defaultMediaArgs.isDecorative()) {
      return "";
    }
    if (!defaultMediaArgs.isForceAltValueFromAsset() && StringUtils.isNotEmpty(defaultMediaArgs.getAltText())) {
      return defaultMediaArgs.getAltText();
    }
    return StringUtils.defaultString(getDescription(), getTitle());
  }

  @Override
  public String getDescription() {
    return getPropertyAwareOfArray(DamConstants.DC_DESCRIPTION);
  }

  @Override
  public @NotNull String getPath() {
    return this.damContext.getAsset().getPath();
  }

  @Override
  public @NotNull ValueMap getProperties() {
    return new ValueMapDecorator(damAsset.getMetadata());
  }

  @Override
  public Rendition getDefaultRendition() {
    return getRendition(this.defaultMediaArgs);
  }

  @Override
  public Rendition getRendition(@NotNull MediaArgs mediaArgs) {
    Rendition rendition = getDamRendition(mediaArgs);

    // check if rendition is valid - otherwise return null
    if (StringUtils.isEmpty(rendition.getUrl())) {
      rendition = null;
    }

    return rendition;
  }

  @Override
  public Rendition getImageRendition(@NotNull MediaArgs mediaArgs) {
    Rendition rendition = getRendition(mediaArgs);
    if (rendition != null && rendition.isImage()) {
      return rendition;
    }
    else {
      return null;
    }
  }

  @SuppressWarnings("deprecation")
  @Override
  public Rendition getFlashRendition(@NotNull MediaArgs mediaArgs) {
    Rendition rendition = getRendition(mediaArgs);
    if (rendition != null && rendition.isFlash()) {
      return rendition;
    }
    else {
      return null;
    }
  }

  @Override
  public Rendition getDownloadRendition(@NotNull MediaArgs mediaArgs) {
    Rendition rendition = getRendition(mediaArgs);
    if (rendition != null && rendition.isDownload()) {
      return rendition;
    }
    else {
      return null;
    }
  }

  /**
   * Get DAM rendition instance.
   * @param mediaArgs Media args
   * @return DAM rendition instance (may be invalid rendition)
   */
  protected Rendition getDamRendition(MediaArgs mediaArgs) {
    return new DamRendition(this.cropDimension, this.rotation, mediaArgs, damContext);
  }

  @Override
  @SuppressWarnings({ "unchecked", "null" })
  public <AdapterType> AdapterType adaptTo(Class<AdapterType> type) {
    if (type == com.day.cq.dam.api.Asset.class) {
      return (AdapterType)this.damContext.getAsset();
    }
    if (type == Resource.class) {
      return (AdapterType)this.damContext.getAsset().adaptTo(Resource.class);
    }
    return super.adaptTo(type);
  }

  @Override
  public @NotNull UriTemplate getUriTemplate(@NotNull UriTemplateType type) {
    String extension = FilenameUtils.getExtension(damContext.getAsset().getName());
    if (!MediaFileType.isImage(extension) || MediaFileType.isVectorImage(extension)) {
      throw new UnsupportedOperationException("Unable to build URI template for this asset type: " + getPath());
    }
    com.day.cq.dam.api.Rendition original = damContext.getAsset().getOriginal();
    Dimension dimension = AssetRendition.getDimension(original);
    if (dimension == null) {
      throw new IllegalArgumentException("Unable to get dimension for original rendition of asset: " + getPath());
    }
    return new DamUriTemplate(type, dimension, original, null, null, null, damContext);
  }

  @Override
  public String toString() {
    return ToStringBuilder.reflectionToString(this, ToStringStyle.SHORT_PREFIX_OMIT_NULL_STYLE);
  }

}
