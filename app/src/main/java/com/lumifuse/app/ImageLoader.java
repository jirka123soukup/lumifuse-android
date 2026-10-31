package com.lumifuse.app;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import androidx.exifinterface.media.ExifInterface;
import java.io.IOException;
import java.io.InputStream;

/** Zavírá vstupní proudy, opravuje orientaci podle EXIF a zmenšuje podle delší strany. */
final class ImageLoader {
    private ImageLoader() {}

    static Bitmap load(ContentResolver resolver, Uri uri, int maxEdge) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        try (InputStream input = open(resolver, uri)) {
            if (input.read() != 0xff || input.read() != 0xd8) {
                throw new IOException("Vyberte fotky ve formátu JPG");
            }
        }
        options.inJustDecodeBounds = true;
        try (InputStream input = open(resolver, uri)) {
            BitmapFactory.decodeStream(input, null, options);
        }
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            throw new IOException("Fotka je poškozená nebo má nerozpoznaný formát");
        }
        options.inSampleSize = 1;
        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > maxEdge * 2) {
            options.inSampleSize *= 2;
        }
        options.inJustDecodeBounds = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap decoded;
        try (InputStream input = open(resolver, uri)) {
            decoded = BitmapFactory.decodeStream(input, null, options);
        }
        if (decoded == null) throw new IOException("Fotku se nepodařilo dekódovat");
        try {
            int orientation;
            try (InputStream input = open(resolver, uri)) {
                orientation = new ExifInterface(input).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            }
            Matrix matrix = new Matrix();
            switch (orientation) {
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: matrix.setScale(-1, 1); break;
                case ExifInterface.ORIENTATION_ROTATE_180: matrix.setRotate(180); break;
                case ExifInterface.ORIENTATION_FLIP_VERTICAL: matrix.setScale(1, -1); break;
                case ExifInterface.ORIENTATION_TRANSPOSE:
                    matrix.setRotate(90); matrix.postScale(-1, 1); break;
                case ExifInterface.ORIENTATION_ROTATE_90: matrix.setRotate(90); break;
                case ExifInterface.ORIENTATION_TRANSVERSE:
                    matrix.setRotate(-90); matrix.postScale(-1, 1); break;
                case ExifInterface.ORIENTATION_ROTATE_270: matrix.setRotate(-90); break;
                default: break;
            }
            Bitmap oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(),
                    decoded.getHeight(), matrix, true);
            if (oriented != decoded) decoded.recycle();
            decoded = oriented;
            float scale = Math.min(1f, (float) maxEdge / Math.max(decoded.getWidth(), decoded.getHeight()));
            Bitmap resized = Bitmap.createScaledBitmap(decoded,
                    Math.max(1, Math.round(decoded.getWidth() * scale)),
                    Math.max(1, Math.round(decoded.getHeight() * scale)), true);
            if (resized != decoded) decoded.recycle();
            return resized;
        } catch (IOException | RuntimeException error) {
            decoded.recycle();
            throw error;
        }
    }

    private static InputStream open(ContentResolver resolver, Uri uri) throws IOException {
        InputStream input = resolver.openInputStream(uri);
        if (input == null) throw new IOException("Fotku se nepodařilo načíst, vyberte ji znovu");
        return input;
    }
}
