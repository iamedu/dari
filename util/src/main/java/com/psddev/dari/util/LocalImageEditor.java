package com.psddev.dari.util;

import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.servlet.http.HttpServletRequest;
import org.imgscalr.Scalr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LocalImageEditor extends AbstractImageEditor {

    private static final String DEFAULT_IMAGE_FORMAT = "png";
    private static final String DEFAULT_IMAGE_CONTENT_TYPE = "image/" + DEFAULT_IMAGE_FORMAT;
    /** Setting key for quality to use for the output images. */
    private static final String QUALITY_SETTING = "quality";

    protected static final String THUMBNAIL_COMMAND = "thumbnail";
    private static final Logger LOGGER = LoggerFactory.getLogger(LocalImageEditor.class);

    private Scalr.Method quality = Scalr.Method.AUTOMATIC;

    public Scalr.Method getQuality() {
        return quality;
    }

    public void setQuality(Scalr.Method quality) {
        this.quality = quality;
    }

    @Override
    public StorageItem edit(StorageItem storageItem, String command, Map<String, Object> options, Object... arguments) {
        BufferedImage bufferedImage = null;
        ByteArrayOutputStream ouputStream = new ByteArrayOutputStream();

        try {
            StringBuilder imageUrl = new StringBuilder();
            if (storageItem.getPublicUrl().startsWith("http") || PageContextFilter.Static.getRequest() == null) {
                imageUrl.append(storageItem.getPublicUrl());
            } else {
                HttpServletRequest request = PageContextFilter.Static.getRequest();

                imageUrl.append("http");
                if (request.isSecure()) {
                    imageUrl.append("s");
                }
                imageUrl.append("://");
                imageUrl.append(request.getServerName());

                if (request.getServerPort() != 80 && request.getServerPort() != 443) {
                    imageUrl.append(":")
                            .append(request.getServerPort());
                }
                imageUrl.append(storageItem.getPublicUrl());

            }

            if (storageItem.getPublicUrl().endsWith("tif") || storageItem.getPublicUrl().endsWith("tiff")) {
                //TODO add support for tif
            } else {
                bufferedImage = ImageIO.read(new URL(imageUrl.toString()));
            }

            if (bufferedImage == null) {
                 LOGGER.error("can't read image " + imageUrl.toString());
            }

            if (bufferedImage != null) {
                Object cropOption = options != null ? options.get(ImageEditor.CROP_OPTION) : null;

                if (ImageEditor.CROP_COMMAND.equals(command) &&
                        options != null &&
                        options.containsKey(ImageEditor.CROP_OPTION) &&
                        options.get(ImageEditor.CROP_OPTION).equals(ImageEditor.CROP_OPTION_NONE)) {
                    return storageItem;
                }

                Integer width = null;
                Integer height = null;

                String url = storageItem.getPublicUrl();

                if (url.contains("/" + THUMBNAIL_COMMAND + "/")) {
                    return storageItem;
                }

                boolean newLocalImage = !url.contains(LocalImageServlet.LEGACY_PATH);

                StringBuilder path = new StringBuilder();
                if (newLocalImage) {
                    path.append(LocalImageServlet.LEGACY_PATH);
                } else {
                    path.append("/");
                }

                if (ImageEditor.CROP_COMMAND.equals(command) &&
                        ObjectUtils.to(Integer.class, arguments[0]) == null  &&
                        ObjectUtils.to(Integer.class, arguments[1]) == null) {
                    path.append(THUMBNAIL_COMMAND);
                    command = RESIZE_COMMAND;
                    arguments[0] = arguments[2];
                    arguments[1] = arguments[3];
                } else {
                    path.append(command);
                }
                path.append("/");

                if (ImageEditor.CROP_COMMAND.equals(command)) {
                    Integer x = ObjectUtils.to(Integer.class, arguments[0]);
                    Integer y = ObjectUtils.to(Integer.class, arguments[1]);
                    width = ObjectUtils.to(Integer.class, arguments[2]);
                    height = ObjectUtils.to(Integer.class, arguments[3]);
                    bufferedImage = crop(bufferedImage, x, y, width, height);

                    path.append(x)
                        .append("x")
                        .append(y)
                        .append("x")
                        .append(width)
                        .append("x")
                        .append(height);

                } else if (ImageEditor.RESIZE_COMMAND.equals(command)) {
                    width = ObjectUtils.to(Integer.class, arguments[0]);
                    height = ObjectUtils.to(Integer.class, arguments[1]);
                    bufferedImage = reSize(bufferedImage, width, height, null, null);

                    if (width != null) {
                        path.append(width);
                    }
                    path.append("x");
                    if (height != null) {
                        path.append(height);
                    }
                    Object resizeOption = options != null ? options.get(ImageEditor.RESIZE_OPTION) : null;

                    if (resizeOption != null &&
                            (cropOption == null || !cropOption.equals(ImageEditor.CROP_OPTION_AUTOMATIC))) {
                        if (resizeOption.equals(ImageEditor.RESIZE_OPTION_IGNORE_ASPECT_RATIO)) {
                            path.append("!");
                        } else if (resizeOption.equals(ImageEditor.RESIZE_OPTION_ONLY_SHRINK_LARGER)) {
                            path.append(">");
                        } else if (resizeOption.equals(ImageEditor.RESIZE_OPTION_ONLY_ENLARGE_SMALLER)) {
                            path.append("<");
                        } else if (resizeOption.equals(ImageEditor.RESIZE_OPTION_FILL_AREA)) {
                            path.append("^");
                        }
                    }

                }

                if (newLocalImage) {
                    path.append("?url=").append(storageItem.getPublicUrl());
                } else {
                    String[] imageParameters = storageItem.getPublicUrl().split("\\?");
                    path.insert(0, imageParameters[0])
                        .append("?")
                        .append(imageParameters[1]);
                }

                UrlStorageItem newStorageItem = StorageItem.Static.createUrl("");

                String format = DEFAULT_IMAGE_FORMAT;
                String contentType = DEFAULT_IMAGE_CONTENT_TYPE;
                if (storageItem.getContentType() != null && storageItem.getContentType().contains("/")) {
                    contentType = storageItem.getContentType();
                    format = storageItem.getContentType().split("/")[1];
                }
                ImageIO.write(bufferedImage, format, ouputStream);
                newStorageItem.setData(new ByteArrayInputStream(ouputStream.toByteArray()));
                newStorageItem.setContentType(contentType);

                Map<String, Object> metaData = storageItem.getMetadata();
                if (metaData == null) {
                    metaData = new HashMap<String, Object>();
                }
                metaData.put("height", height);
                metaData.put("width", width);
                newStorageItem.setMetadata(metaData);
                newStorageItem.setStorage("");
                newStorageItem.setPath(path.toString());
                return newStorageItem;
            }
        } catch (MalformedURLException ex) {
            LOGGER.error(ex.getMessage(), ex);
        } catch (IOException ex) {
            LOGGER.error(ex.getMessage(), ex);
        }

        return storageItem;
    }

    @Override
    public void initialize(String settingsKey, Map<String, Object> settings) {
        Object qualitySetting = settings.get(QUALITY_SETTING);
        if (qualitySetting == null) {
            qualitySetting = Settings.get(QUALITY_SETTING);
        }

        if (qualitySetting != null) {
            if (qualitySetting instanceof Integer) {
                Integer qualityInteger = ObjectUtils.to(Integer.class, qualitySetting);
                quality = findQualityByInteger(qualityInteger);
            } else if (qualitySetting instanceof String) {
                quality = Scalr.Method.valueOf(ObjectUtils.to(String.class, qualitySetting));
            }
        }
    }

    protected static Scalr.Method findQualityByInteger(Integer quality) {
        if (quality >= 80) {
            return Scalr.Method.ULTRA_QUALITY;
        } else if (quality >= 60) {
            return Scalr.Method.QUALITY;
        } else if (quality >= 40) {
            return Scalr.Method.AUTOMATIC;
        } else if (quality >= 20) {
            return Scalr.Method.BALANCED;
        } else {
            return Scalr.Method.SPEED;
        }
    }

    /** Helper class so that width and height can be returned in a single object */
    protected static class Dimension {
        public final Integer width;
        public final Integer height;
        public Dimension(Integer width, Integer height) {
            this.width = width;
            this.height = height;
        }
    }

    public BufferedImage reSize(BufferedImage bufferedImage, Integer width, Integer height, String option, Scalr.Method quality) {
        if (quality == null) {
            quality = this.quality;
        }
        if (width != null || height != null) {
            if (!StringUtils.isBlank(option) &&
                    option.equals(ImageEditor.RESIZE_OPTION_ONLY_SHRINK_LARGER)) {
                if ((height == null && width >= bufferedImage.getWidth()) ||
                            (width == null && height >= bufferedImage.getHeight()) ||
                            (width != null && height != null && width >= bufferedImage.getWidth() && height >= bufferedImage.getHeight())) {
                    return bufferedImage;
                }

            } else if (!StringUtils.isBlank(option) &&
                    option.equals(ImageEditor.RESIZE_OPTION_ONLY_ENLARGE_SMALLER)) {
                if ((height == null && width <= bufferedImage.getWidth()) ||
                            (width == null && height <= bufferedImage.getHeight()) ||
                            (width != null && height != null && (width <= bufferedImage.getWidth() || height <= bufferedImage.getHeight()))) {
                    return bufferedImage;
                }
            }

            if (StringUtils.isBlank(option) ||
                    option.equals(ImageEditor.RESIZE_OPTION_ONLY_SHRINK_LARGER) ||
                    option.equals(ImageEditor.RESIZE_OPTION_ONLY_ENLARGE_SMALLER)) {
                if (height == null) {
                    return Scalr.resize(bufferedImage, quality, Scalr.Mode.FIT_TO_WIDTH, width);
                } else if (width == null) {
                    return Scalr.resize(bufferedImage, quality, Scalr.Mode.FIT_TO_HEIGHT, height);
                } else {
                    return Scalr.resize(bufferedImage, quality, width, height);
                }

            } else if (height != null && width != null) {
                if (option.equals(ImageEditor.RESIZE_OPTION_IGNORE_ASPECT_RATIO)) {
                    return Scalr.resize(bufferedImage, quality, Scalr.Mode.FIT_EXACT, width, height);
                } else if (option.equals(ImageEditor.RESIZE_OPTION_FILL_AREA)) {
                    Dimension dimension = getFillAreaDimension(bufferedImage.getWidth(), bufferedImage.getHeight(), width, height);
                    return Scalr.resize(bufferedImage, quality, Scalr.Mode.FIT_EXACT, dimension.width, dimension.height);
                }

            }

        }
        return null;
    }

    public static BufferedImage crop(BufferedImage bufferedImage, Integer x, Integer y, Integer width, Integer height) {

        if (width != null || height != null) {
            if (height == null) {
                height = (int) ((double) bufferedImage.getHeight() / (double) bufferedImage.getWidth() * (double) width);
            } else if (width == null) {
                width = (int) ((double) bufferedImage.getWidth() / (double) bufferedImage.getHeight() * (double) height);
            }

            if (x == null) {
                x = bufferedImage.getWidth() / 2;
            }

            if (y == null) {
                y = bufferedImage.getHeight() / 2;
            }

            return Scalr.crop(bufferedImage, x, y, width, height);
        }

        return null;
    }

    public static BufferedImage grayscale(BufferedImage sourceImage) {
        BufferedImage resultImage = new BufferedImage(sourceImage.getWidth(), sourceImage.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        Graphics g = resultImage.getGraphics();
        g.drawImage(sourceImage, 0, 0, null);
        g.dispose();
        return resultImage;
    }

    public static BufferedImage brightness(BufferedImage sourceImage, int brightness, int contrast) {
        BufferedImage resultImage =  new BufferedImage(sourceImage.getWidth(), sourceImage.getHeight(), sourceImage.getType());

        int multiply = 100;
        int add;

        if (contrast == 0) {
            add = Math.round(brightness / 100.0f * 255);
        } else {
            if (contrast > 0) {
                contrast = contrast * 4;
            }
            contrast = 100 - (contrast * -1);
            multiply = contrast;
            brightness = Math.round(brightness / 100.0f * 255);

            add = ((Double) (((brightness - 128) * (multiply / 100.0d) + 128))).intValue();

        }

        for (int x = 0; x < sourceImage.getWidth(); x++) {
            for (int y = 0; y < sourceImage.getHeight(); y++) {
                int rgb = sourceImage.getRGB(x, y);
                int alpha = (rgb >> 24) & 0xFF;
                int red   = (rgb >> 16) & 0xFF;
                int green = (rgb >> 8) & 0xFF;
                int blue  = rgb & 0xFF;

                red = adjustColor(red, multiply, add);
                green = adjustColor(green, multiply, add);
                blue = adjustColor(blue, multiply, add);

                int newRgb = (alpha << 24) | (red << 16) | (green << 8) | blue;

                resultImage.setRGB(x, y, newRgb);
            }
        }

        return resultImage;
    }

    public static BufferedImage flipHorizontal(BufferedImage sourceImage) {
        BufferedImage resultImage =  new BufferedImage(sourceImage.getWidth(), sourceImage.getHeight(), sourceImage.getType());
        int width = sourceImage.getWidth();

        for (int x = 0; x < (width / 2); x++) {
            for (int y = 0; y < sourceImage.getHeight(); y++) {
                int sourceX = sourceImage.getWidth() - x - 1;
                resultImage.setRGB(x, y, sourceImage.getRGB(sourceX, y));
                resultImage.setRGB(sourceX, y, sourceImage.getRGB(x, y));
            }
        }

        //odd width size copy center pixel
        if (width % 2 == 1) {
            int x = (width / 2);
            for (int y = 0; y < sourceImage.getHeight(); y++) {
                resultImage.setRGB(x, y, sourceImage.getRGB(x, y));
            }
        }

        return resultImage;
    }

    public static BufferedImage flipVertical(BufferedImage sourceImage) {
        BufferedImage resultImage =  new BufferedImage(sourceImage.getWidth(), sourceImage.getHeight(), sourceImage.getType());
        int height = sourceImage.getWidth();

        for (int y = 0; y < (height / 2); y++) {
            for (int x = 0; x < sourceImage.getWidth(); x++) {
                int sourceY = sourceImage.getHeight() - y - 1;
                resultImage.setRGB(x, y, sourceImage.getRGB(x, sourceY));
                resultImage.setRGB(x, sourceY, sourceImage.getRGB(x, y));
            }
        }

        //odd height size copy center pixel
        if (height % 2 == 1) {
            int y = (height / 2);
            for (int x = 0; x < sourceImage.getHeight(); x++) {
                resultImage.setRGB(x, y, sourceImage.getRGB(x, y));
            }
        }

        return resultImage;
    }

    public static BufferedImage invert(BufferedImage sourceImage) {
        BufferedImage resultImage =  new BufferedImage(sourceImage.getWidth(), sourceImage.getHeight(), sourceImage.getType());

        for (int x = 0; x < sourceImage.getWidth(); x++) {
            for (int y = 0; y < sourceImage.getHeight(); y++) {
                int rgb = sourceImage.getRGB(x, y);
                int alpha = (rgb >> 24) & 0xFF;
                int red   = 255 - (rgb >> 16) & 0xFF;
                int green = 255 - (rgb >> 8) & 0xFF;
                int blue  = 255 - rgb & 0xFF;

                int newRgb = (alpha << 24) | (red << 16) | (green << 8) | blue;

                resultImage.setRGB(x, y, newRgb);
            }
        }

        return resultImage;
    }

    private static int adjustColor(int color, int multiply, int add) {
        color =  Math.round(color * (multiply / 100.0f)) + add;
        if (color > 255) {
            color = 255;
        } else if (color < 0) {
            color = 0;
        }
        return color;
    }

    private static Dimension getFillAreaDimension(Integer originalWidth, Integer originalHeight, Integer requestedWidth, Integer requestedHeight) {
        Integer actualWidth = null;
        Integer actualHeight = null;

        if (originalWidth != null && originalHeight != null &&
                (requestedWidth != null || requestedHeight != null)) {

            float originalRatio = (float) originalWidth / (float) originalHeight;
            if (requestedWidth != null && requestedHeight != null) {

                Integer potentialWidth = Math.round((float) requestedHeight * originalRatio);
                Integer potentialHeight = Math.round((float) requestedWidth / originalRatio);

                if (potentialWidth > requestedWidth) {
                    actualWidth = potentialWidth;
                    actualHeight = requestedHeight;

                } else { // potentialHeight > requestedHeight
                    actualWidth = requestedWidth;
                    actualHeight = potentialHeight;
                }

            } else if (originalWidth > originalHeight) {
                actualHeight = requestedHeight != null ? requestedHeight : requestedWidth;
                actualWidth = Math.round((float) actualHeight * originalRatio);

            } else { // originalWidth <= originalHeight
                actualWidth = requestedWidth != null ? requestedWidth : requestedHeight;
                actualHeight = Math.round((float) actualWidth / originalRatio);
            }
        }

        return new Dimension(actualWidth, actualHeight);
    }

}
