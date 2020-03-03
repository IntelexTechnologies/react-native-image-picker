package com.imagepicker.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;
import android.text.TextUtils;

import com.facebook.react.bridge.ReadableMap;
import com.imagepicker.ImagePickerModule;
import com.imagepicker.ResponseHelper;
import com.imagepicker.media.ImageConfig;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.TimeZone;
import java.util.UUID;

import static com.imagepicker.ImagePickerModule.REQUEST_LAUNCH_IMAGE_CAPTURE;

/**
 * Created by rusfearuth on 15.03.17.
 */

public class MediaUtils
{
    public static @Nullable File createNewFile(@NonNull final Context reactContext,
                                               @NonNull final ReadableMap options,
                                               @NonNull final boolean forceLocal,
                                               @NonNull String extension)
    {
        final String filename = new StringBuilder("image-")
                .append(UUID.randomUUID().toString())
                .append(".")
                .append(extension)
                .toString();

        final File path = ReadableMapUtils.hasAndNotNullReadableMap(options, "storageOptions")
                && ReadableMapUtils.hasAndNotEmptyString(options.getMap("storageOptions"), "path")
                ? new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), options.getMap("storageOptions").getString("path"))
                : (!forceLocal ? Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                              : reactContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES));

        File result = new File(path, filename);

        try
        {
            path.mkdirs();
            result.createNewFile();
        }
        catch (IOException e)
        {
            e.printStackTrace();
            result = null;
        }

        return result;
    }

    public static @Nullable File createNewFile(@NonNull final Context reactContext,
                                               @NonNull final ReadableMap options,
                                               @NonNull final boolean forceLocal,
                                               @NonNull String extension,
                                               final String filename)
    {
       final File path = ReadableMapUtils.hasAndNotNullReadableMap(options, "storageOptions")
                && ReadableMapUtils.hasAndNotEmptyString(options.getMap("storageOptions"), "path")
                ? new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), options.getMap("storageOptions").getString("path"))
                : (!forceLocal ? Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                              : reactContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES));

        File result = new File(path, filename);

        try
        {
            path.mkdirs();
            result.createNewFile();
        }
        catch (IOException e)
        {
            e.printStackTrace();
            result = null;
        }

        return result;
    }

    /**
     * Create a resized image to fulfill the maxWidth/maxHeight, quality and rotation values
     *
     * @param context
     * @param options
     * @param imageConfig
     * @param initialWidth
     * @param initialHeight
     * @return updated ImageConfig
     */
    public static @NonNull ImageConfig getResizedImage(@NonNull final Context context,
                                                       @NonNull final ReadableMap options,
                                                       @NonNull final ImageConfig imageConfig,
                                                       int initialWidth,
                                                       int initialHeight,
                                                       Boolean forceLocal,
                                                       String extension,
                                                       @NonNull ResponseHelper responseHelper,
                                                       final int requestCode)
    {
        BitmapFactory.Options imageOptions = new BitmapFactory.Options();
        imageOptions.inScaled = false;
        SampleSizeRatioCalculation sampleSizeAndRatio = calculateInSampleSizeAndRatio(initialWidth, initialHeight, imageConfig.maxWidth, imageConfig.maxHeight);
        imageOptions.inSampleSize = sampleSizeAndRatio.InSampleSize;

        Bitmap photo = BitmapFactory.decodeFile(imageConfig.original.getAbsolutePath(), imageOptions);

        if (photo == null)
        {
            return null;
        }

        ImageConfig result = imageConfig;

        Bitmap scaledPhoto = null;
        if (imageConfig.maxWidth == 0 || imageConfig.maxWidth > initialWidth)
        {
            result = result.withMaxWidth(initialWidth);
        }
        if (imageConfig.maxHeight == 0 || imageConfig.maxHeight > initialHeight)
        {
            result = result.withMaxHeight(initialHeight);
        }

        double postScaleRatio = sampleSizeAndRatio.DesiredScalingRatio * sampleSizeAndRatio.InSampleSize;
        Matrix matrix = new Matrix();
        matrix.postRotate(result.rotation);
        matrix.postScale((float) postScaleRatio, (float) postScaleRatio);

        ExifInterface exif;
        try
        {
            exif = new ExifInterface(result.original.getAbsolutePath());

            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0);

            switch (orientation)
            {
                case 6:
                    matrix.postRotate(90);
                    responseHelper.putInt("originalRotation", 0);
                    break;
                case 3:
                    matrix.postRotate(180);
                    responseHelper.putInt("originalRotation", 0);
                    break;
                case 8:
                    matrix.postRotate(270);
                    responseHelper.putInt("originalRotation", 0);
                    break;
            }
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
        
        scaledPhoto = Bitmap.createBitmap(photo, 0, 0, photo.getWidth(), photo.getHeight(), matrix, true);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Bitmap.CompressFormat format = extension.equals("png") ? Bitmap.CompressFormat.PNG: Bitmap.CompressFormat.JPEG;
        scaledPhoto.compress(format, imageConfig.quality, bytes);

        String originalName = imageConfig.original.getName();
        String[] attachmentsConvertJPG = {};
        if (options.hasKey("attachmentsConvertJPG")) attachmentsConvertJPG = options.getString("attachmentsConvertJPG").split(",");
        for (String type : attachmentsConvertJPG) {
            if (extension.equals(type)) extension = "jpg";
        }
        String[] originalNameArr = originalName.split("\\.");
        originalNameArr[originalNameArr.length - 1] = extension;
        String newFileName = TextUtils.join(".", originalNameArr);

        final boolean finalForceLocal = (requestCode != REQUEST_LAUNCH_IMAGE_CAPTURE) || forceLocal;
        final File resized = createNewFile(context, options, finalForceLocal, extension, newFileName);

        if (resized == null)
        {
            if (photo != null)
            {
                photo.recycle();
                photo = null;
            }
            if (scaledPhoto != null)
            {
                scaledPhoto.recycle();
                scaledPhoto = null;
            }
            return imageConfig;
        }

        result = result.withResizedFile(resized);

        try (FileOutputStream fos = new FileOutputStream(result.resized))
        {
            bytes.writeTo(fos);
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }

        if (photo != null)
        {
            photo.recycle();
            photo = null;
        }
        if (scaledPhoto != null)
        {
            scaledPhoto.recycle();
            scaledPhoto = null;
        }
        return result;
    }

    public static void removeUselessFiles(final int requestCode,
                                          @NonNull final ImageConfig imageConfig)
    {
        if (requestCode != ImagePickerModule.REQUEST_LAUNCH_IMAGE_CAPTURE)
        {
            return;
        }

        if (imageConfig.original != null && imageConfig.original.exists())
        {
            imageConfig.original.delete();
        }

        if (imageConfig.resized != null && imageConfig.resized.exists())
        {
            imageConfig.resized.delete();
        }
    }

    public static void fileScan(@Nullable final Context reactContext,
                                @NonNull final String path)
    {
        if (reactContext == null)
        {
            return;
        }
        MediaScannerConnection.scanFile(reactContext,
                new String[] { path }, null,
                new MediaScannerConnection.OnScanCompletedListener()
                {
                    public void onScanCompleted(String path, Uri uri)
                    {
                        Log.i("TAG", new StringBuilder("Finished scanning ").append(path).toString());
                    }
                });
    }

    public static ReadExifResult readExifInterface(@NonNull ResponseHelper responseHelper,
                                                   @NonNull final ImageConfig imageConfig)
    {
        ReadExifResult result;
        int currentRotation = 0;

        try
        {
            ExifInterface exif = new ExifInterface(imageConfig.original.getAbsolutePath());

            // extract lat, long, and timestamp and add to the response
            float[] latlng = new float[2];
            exif.getLatLong(latlng);
            float latitude = latlng[0];
            float longitude = latlng[1];
            if(latitude != 0f || longitude != 0f)
            {
                responseHelper.putDouble("latitude", latitude);
                responseHelper.putDouble("longitude", longitude);
            }

            final String timestamp = exif.getAttribute(ExifInterface.TAG_DATETIME);
            final SimpleDateFormat exifDatetimeFormat = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss");

            final DateFormat isoFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
            isoFormat.setTimeZone(TimeZone.getTimeZone("UTC"));

            try
            {
                final String isoFormatString = new StringBuilder(isoFormat.format(exifDatetimeFormat.parse(timestamp)))
                        .append("Z").toString();
                responseHelper.putString("timestamp", isoFormatString);
            }
            catch (Exception e) {}

            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            boolean isVertical = true;
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_270:
                    isVertical = false;
                    currentRotation = 270;
                    break;
                case ExifInterface.ORIENTATION_ROTATE_90:
                    isVertical = false;
                    currentRotation = 90;
                    break;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    currentRotation = 180;
                    break;
            }
            responseHelper.putInt("originalRotation", currentRotation);
            responseHelper.putBoolean("isVertical", isVertical);
            result = new ReadExifResult(currentRotation, null);
        }
        catch (IOException e)
        {
            e.printStackTrace();
            result = new ReadExifResult(currentRotation, e);
        }

        return result;
    }

    public static @Nullable RolloutPhotoResult rolloutPhotoFromCamera(@NonNull final ImageConfig imageConfig)
    {
        RolloutPhotoResult result = null;
        final File oldFile = imageConfig.resized == null ? imageConfig.original: imageConfig.resized;
        final File newDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
        final File newFile = new File(newDir.getPath(), oldFile.getName());

        try
        {
            moveFile(oldFile, newFile);
            ImageConfig newImageConfig;
            if (imageConfig.resized != null)
            {
                newImageConfig = imageConfig.withResizedFile(newFile);
            }
            else
            {
                newImageConfig = imageConfig.withOriginalFile(newFile);
            }
            result = new RolloutPhotoResult(newImageConfig, null);
        }
        catch (IOException e)
        {
            e.printStackTrace();
            result = new RolloutPhotoResult(imageConfig, e);
        }
        return result;
    }

    /**
     * Move a file from one location to another.
     *
     * This is done via copy + deletion, because Android will throw an error
     * if you try to move a file across mount points, e.g. to the SD card.
     */
    private static void moveFile(@NonNull final File oldFile,
                                 @NonNull final File newFile) throws IOException
    {
        FileChannel oldChannel = null;
        FileChannel newChannel = null;

        try
        {
            oldChannel = new FileInputStream(oldFile).getChannel();
            newChannel = new FileOutputStream(newFile).getChannel();
            oldChannel.transferTo(0, oldChannel.size(), newChannel);

            oldFile.delete();
        }
        finally
        {
            try
            {
                if (oldChannel != null) oldChannel.close();
                if (newChannel != null) newChannel.close();
            }
            catch (IOException e)
            {
                e.printStackTrace();
            }
        }
    }


    public static class RolloutPhotoResult
    {
        public final ImageConfig imageConfig;
        public final Throwable error;

        public RolloutPhotoResult(@NonNull final ImageConfig imageConfig,
                                  @Nullable final Throwable error)
        {
            this.imageConfig = imageConfig;
            this.error = error;
        }
    }


    public static class ReadExifResult
    {
        public final int currentRotation;
        public final Throwable error;

        public ReadExifResult(int currentRotation,
                              @Nullable final Throwable error)
        {
            this.currentRotation = currentRotation;
            this.error = error;
        }
    }

    public static String getExtensionFromFile(String filename) {
        return filename.isEmpty() ? "": filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
    }

    private static SampleSizeRatioCalculation calculateInSampleSizeAndRatio(int width, int height, int reqWidth, int reqHeight) {
        SampleSizeRatioCalculation calc = new SampleSizeRatioCalculation();
        double desiredWidthRatio = 1.0;
        double desiredHeightRatio = 1.0;
        if (reqWidth != 0 && reqWidth < width) {
            desiredWidthRatio = (double)width / (double)reqWidth;
        }
        if (reqHeight != 0 && reqHeight < height) {
            desiredHeightRatio = (double)height / (double)reqHeight;
        }
        double targetRatio = desiredWidthRatio > desiredHeightRatio ? desiredWidthRatio : desiredHeightRatio;
        calc.DesiredScalingRatio = 1.0 / targetRatio;
        while (targetRatio / 2 > 1.0) {
            calc.InSampleSize *= 2;
            targetRatio /= 2;
        }
        return calc;
    }

}

class SampleSizeRatioCalculation {
    public int InSampleSize = 1;
    public double DesiredScalingRatio = 1.0;
}
