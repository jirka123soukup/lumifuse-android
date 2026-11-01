package com.lumifuse.app;

import android.graphics.Bitmap;
import org.opencv.android.Utils;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import java.util.Arrays;

/** Úpravy po sloučení: komprese dynamiky, stíny/světla, expozice, kontrast, sytost, teplota, ostrost. */
final class Adjust {
    private Adjust() {}

    static final class Params {
        float compression;   // 0 … 0.7  – stlačení rozdílu mezi tmavými a světlými oblastmi
        float shadows;       // -1 … 1   – zesvětlení/ztmavení tmavých oblastí
        float highlights;    // -1 … 1   – zesvětlení/ztmavení světlých oblastí
        float exposure;      // EV
        float contrast;      // -0.5 … 0.5
        float saturation;    // -0.8 … 0.8
        float warmth;        // -1 … 1
        float sharpness;     // 0 … 1.5

        boolean isNeutral() {
            return compression == 0 && shadows == 0 && highlights == 0 && exposure == 0
                    && contrast == 0 && saturation == 0 && warmth == 0 && sharpness == 0;
        }
    }

    /**
     * Vrátí nový upravený bitmap; vstup se nemění.
     * @param detailScale poměr velikosti vstupu k plnému výsledku (u náhledu < 1), aby doostření vypadalo stejně
     */
    static Bitmap apply(Bitmap source, Params p, double detailScale) {
        Mat rgba = new Mat(), f = new Mat(), lum = new Mat(), out = new Mat();
        try {
            Utils.bitmapToMat(source, rgba);
            Imgproc.cvtColor(rgba, f, Imgproc.COLOR_RGBA2RGB);
            f.convertTo(f, CvType.CV_32FC3, 1.0 / 255);

            // Expozice a teplota (vyvážení bílé) – násobení kanálů R, G, B.
            double ev = Math.pow(2, p.exposure);
            if (ev != 1 || p.warmth != 0) {
                Core.multiply(f, new Scalar(ev * (1 + 0.12 * p.warmth), ev, ev * (1 - 0.12 * p.warmth)), f);
            }

            // Lokální tónování podle rozmazaného jasu (base) – komprese dynamiky, stíny a světla.
            if (p.compression != 0 || p.shadows != 0 || p.highlights != 0) {
                Mat base = new Mat(), small = new Mat(), gain = new Mat(), tmp = new Mat(), gain3 = new Mat();
                try {
                    Imgproc.cvtColor(f, lum, Imgproc.COLOR_RGB2GRAY);
                    double s = Math.min(1.0, 256.0 / Math.max(lum.cols(), lum.rows()));
                    Imgproc.resize(lum, small, new Size(Math.max(1, lum.cols() * s), Math.max(1, lum.rows() * s)), 0, 0, Imgproc.INTER_AREA);
                    Imgproc.GaussianBlur(small, small, new Size(0, 0), Math.max(1.0, Math.max(small.cols(), small.rows()) / 40.0));
                    Imgproc.resize(small, base, lum.size(), 0, 0, Imgproc.INTER_LINEAR);
                    Core.min(base, new Scalar(1.0), base);
                    Core.max(base, new Scalar(0.0), base);

                    // Komprese: zisk = ((base + 0.02) / 0.5) ^ (-c) – tmavé oblasti zesílí, světlé zeslabí, střední tóny zůstanou.
                    if (p.compression != 0) {
                        Core.add(base, new Scalar(0.02), tmp);
                        Core.multiply(tmp, new Scalar(2.0), tmp);
                        Core.pow(tmp, -p.compression, gain);
                    } else {
                        gain = new Mat(base.size(), CvType.CV_32F, new Scalar(1.0));
                    }
                    // Stíny: působí hlavně tam, kde je base tmavé – maska (1 - base)^3.
                    if (p.shadows != 0) {
                        Core.multiply(base, new Scalar(-1.0), tmp);
                        Core.add(tmp, new Scalar(1.0), tmp);
                        Core.pow(tmp, 3, tmp);
                        Core.multiply(tmp, new Scalar(1.5 * p.shadows), tmp);
                        Core.add(tmp, new Scalar(1.0), tmp);
                        Core.multiply(gain, tmp, gain);
                    }
                    // Světla: maska base^3.
                    if (p.highlights != 0) {
                        Core.pow(base, 3, tmp);
                        Core.multiply(tmp, new Scalar(0.6 * p.highlights), tmp);
                        Core.add(tmp, new Scalar(1.0), tmp);
                        Core.multiply(gain, tmp, gain);
                    }
                    Core.max(gain, new Scalar(0.05), gain);
                    Core.merge(Arrays.asList(gain, gain, gain), gain3);
                    Core.multiply(f, gain3, f);
                } finally {
                    base.release(); small.release(); gain.release(); tmp.release(); gain3.release();
                }
            }

            // Kontrast kolem středního tónu.
            if (p.contrast != 0) {
                double k = 1 + p.contrast;
                Core.subtract(f, new Scalar(0.5, 0.5, 0.5), f);
                Core.multiply(f, new Scalar(k, k, k), f);
                Core.add(f, new Scalar(0.5, 0.5, 0.5), f);
            }

            // Sytost: míchání s černobílou verzí.
            if (p.saturation != 0) {
                Mat gray3 = new Mat();
                try {
                    Imgproc.cvtColor(f, lum, Imgproc.COLOR_RGB2GRAY);
                    Core.merge(Arrays.asList(lum, lum, lum), gray3);
                    Core.addWeighted(f, 1 + p.saturation, gray3, -p.saturation, 0, f);
                } finally { gray3.release(); }
            }

            // Doostření (unsharp mask).
            if (p.sharpness != 0) {
                Mat blurred = new Mat();
                try {
                    double sigma = Math.max(0.6, 1.2 * detailScale);
                    Imgproc.GaussianBlur(f, blurred, new Size(0, 0), sigma);
                    Core.addWeighted(f, 1 + p.sharpness, blurred, -p.sharpness, 0, f);
                } finally { blurred.release(); }
            }

            f.convertTo(out, CvType.CV_8UC3, 255);   // ořízne hodnoty do 0–255
            f.release();
            Imgproc.cvtColor(out, rgba, Imgproc.COLOR_RGB2RGBA);
            Bitmap result = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(rgba, result);
            return result;
        } finally {
            rgba.release(); f.release(); lum.release(); out.release();
        }
    }
}
