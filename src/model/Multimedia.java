/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package model;

import java.io.File;
import java.util.HashMap;
//import java.util.PriorityQueue;

import java.awt.image.ColorModel;
import java.awt.image.WritableRaster;
import java.awt.image.BufferedImage;
import org.bytedeco.javacpp.avutil;
import org.bytedeco.javacv.Java2DFrameConverter;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FrameGrabber;

/**
 * Mosaic class.
 */
public class Multimedia {
    private int gap;
    private int frameID;
    private int divisions;
    private int samplingLevel;
    private Metadata metadata;
    private FFmpegFrameGrabber grabber;
    private BufferedImage[] sourceFrame;
    private final HashMap<Integer, Integer[]> framesSamples;
    private static final Java2DFrameConverter TO_BUFFERED_IMAGE = new Java2DFrameConverter();
    
    /**
     * Mosaic Constructor.
     */
    public Multimedia() {
        gap = 0;
        frameID = 0;
        divisions = 0;
        samplingLevel = 0;
        sourceFrame = null;
        metadata = null;
        grabber = null;
        framesSamples = new HashMap<>();
        
        /* Disable FFmpeg verbose */
        avutil.av_log_set_level(avutil.AV_LOG_QUIET);
    }
    
    /**
     * Sample frames each interval with the currnt sampling level.
     * @throws org.bytedeco.javacv.FrameGrabber.Exception Frame grabber exception.
     */
    private void sampleFrames() throws FrameGrabber.Exception {
        /* Reset members */
        framesSamples.clear();
        
        /* Frame split */
        int max = metadata.frames();
        int width = metadata.width();
        int height = metadata.height();
        float dx = (float) width / (float) samplingLevel;
        float dy = (float) height / (float) samplingLevel;
        
        /* For each frame */
        for (int index = 0; index < max; index += gap) {
            grabber.setFrameNumber(index);
            BufferedImage frame = TO_BUFFERED_IMAGE.convert(grabber.grab());
            if (frame == null) break;
            
            Integer[] mean = new Integer[samplingLevel * samplingLevel];
            int k = 0;
            
            /* For each sample */
            float x = 0.0F;
            for (int col = 0; col < samplingLevel; col++, x += dx) {
                int maxI = Math.round(x + dx);
                if (maxI > width) maxI = width;
                
                float y = 0.0F;
                for (int row = 0; row < samplingLevel; row++, y += dy, k++) {
                    int maxJ = Math.round(y + dy);
                    int meanR = 0;
                    int meanG = 0;
                    int meanB = 0;
                    int sum = 0;
                    
                    if (maxJ > height) maxJ = height;
                    
                    /* Get color sum by channel */
                    for (int i = Math.round(x); i < maxI; i++)
                        for (int j = Math.round(y); j < maxJ; j++) {
                            int rgb = frame.getRGB(i, j);
                            meanR += (rgb >> 16) & 0x00FF0000;
                            meanG += (rgb >>  8) & 0x0000FF00;
                            meanB +=  rgb        & 0x000000FF;
                            sum++;
                        }
                    
                    mean[k] = ((meanR / (sum)) << 16) | ((meanG / sum) << 8) | (meanB / sum);
                }
            }
            
            /* Put frame sample */
            framesSamples.put(index, mean.clone());
        }
    }
    
    /**
     * Sample the selected frame with the currnt sampling level.
     * @param frameNumber Number of the frame to mosaicate.
     * @return Samples array with each color mean by level.
     * @throws org.bytedeco.javacv.FrameGrabber.Exception Frame grabber exception.
     */
    private Integer[][] sampleFrame(int frameNumber) throws FrameGrabber.Exception {
        /* Get frame to mosaicate */
        BufferedImage frame = getFrame(frameNumber);
        if (frame == null) return null;
        
        /* Frame split */
        int width = metadata.width();
        int height = metadata.height();
        float sampleWidth = (float) width / (float) divisions;
        float sampleHeight = (float) height / (float) divisions;
        float dx = sampleWidth / (float) samplingLevel;
        float dy = sampleHeight / (float) samplingLevel;
        
        /* For each division */
        Integer[][] frameSample = new Integer[divisions * divisions][samplingLevel * samplingLevel];
        int sample = 0;
        
        float n = 0.0F;
        for (int colD = 0; colD < divisions; colD++, n += sampleWidth) {
            
            float m = 0.0F;
            for (int rowD = 0; rowD < divisions; rowD++, m += sampleHeight, sample++) {
                
                /* For each sample */
                int k = 0;
                float x = n;
                for (int colL = 0; colL < samplingLevel; colL++, x += dx) {
                    int maxI = Math.round(x + dx);
                    if (maxI > width) maxI = Math.round(width);
                        
                    float y = m;
                    for (int rowL = 0; rowL < samplingLevel; rowL++, y += dy, k++) {
                        int maxJ = Math.round(y + dy);
                        int meanR = 0;
                        int meanG = 0;
                        int meanB = 0;
                        int sum = 0;
                        
                        if (maxJ > height) maxJ = Math.round(height);

                        /* Get color sum by channel */
                        for (int i = Math.round(x); i < maxI; i++)
                            for (int j = Math.round(y); j < maxJ; j++) {
                                int rgb = frame.getRGB(i, j);
                                meanR += (rgb >> 16) & 0x00FF0000;
                                meanG += (rgb >>  8) & 0x0000FF00;
                                meanB +=  rgb        & 0x000000FF;
                                sum++;
                            }

                        frameSample[sample][k] = ((meanR / (sum)) << 16) | ((meanG / sum) << 8) | (meanB / sum);
                    }
                }
            }
        }
        
        return frameSample;
    }
    
    /**
     * Search source frames to mosaic and save on sourceFrame.
     * @param frameNumber Number of the frame to mosaicate.
     * @throws org.bytedeco.javacv.FrameGrabber.Exception Frame grabber exception.
     */
    private void searchSources(int frameNumber) throws FrameGrabber.Exception {
        /* Sample selected frame */
        Integer[][] frameSample = sampleFrame(frameNumber);
        
        //PriorityQueue<Integer> used = new PriorityQueue<>();
        int destiny = frameSample.length;
        sourceFrame = new BufferedImage[destiny];
        
        /* For each destiny */
        for (int i = 0; i < destiny; i++) {
            if (frameSample[i][0] == null) break;
            int min = Integer.MAX_VALUE;
            int nearest = 0;
            
            /* For each sampled frame */
            for (HashMap.Entry<Integer, Integer[]> sampled : framesSamples.entrySet()) {
                frameNumber = sampled.getKey();
                //if (used.contains(frameNumber)) continue;
                
                Integer[] mean = sampled.getValue();
                int distance = 0;
                
                /* Manhattan distance */
                for (int j = 0; j < mean.length; j++) {
                    if (frameSample[i][j] == null) break;
                    
                    int rgbDst = frameSample[i][j];
                    int rgbSrc = mean[j];
                    int r = Math.abs(((rgbSrc >> 16) & 0xFF) - ((rgbDst >> 16) & 0xFF));
                    int g = Math.abs(((rgbSrc >>  8) & 0xFF) - ((rgbDst >>  8) & 0xFF));
                    int b = Math.abs(( rgbSrc        & 0xFF) - ( rgbDst        & 0xFF));
                    
                    distance += r + g + b;
                }
                
                /* Get min distance */
                if (distance < min) {
                    min = distance;
                    nearest = frameNumber;
                    //used.add(frameNumber);
                }
            }
            
            /* Store nearest */
            sourceFrame[i] = getFrame(nearest);
        }
    }
    
    /**
     * Build a photomosaic from the selected source frames.
     * @param scale Scale of the photomosaic.
     * @return A photomosaic.
     * @throws FrameGrabber.Exception FrameGrabber exception.
     */
    private BufferedImage buildMosaic(float scale) throws FrameGrabber.Exception {
        int sample = 0;
        int width = Math.round(metadata.width() * scale);
        int height = Math.round(metadata.height() * scale);
        int widthEnd = width - 1;
        int heightEnd = height - 1;
        float pieceWidth = (float) width / (float) divisions;
        float pieceHeight = (float) height / (float) divisions;
        BufferedImage mosaic = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        
        /* For each sample */
        float x = 0.0F;
        for (int col = 0; col < divisions; col++, x += pieceWidth) {
            int maxX = Math.round(x + pieceWidth);
            if (maxX > width) maxX = width;
            
            float y = 0.0F;
            for (int row = 0; row < divisions; row++, y += pieceHeight, sample++) {
                BufferedImage frame = sourceFrame[sample];
                int maxY = Math.round(y + pieceHeight);
                if (maxY > height) maxY = height;
            
                /* Fill source */
                for (int px = Math.round(x), xBegin = px, xEnd = maxX - px - 1; px < maxX; px++)
                    for (int py = Math.round(y), yBegin = py, yEnd = maxY - py - 1; py < maxY; py++) {
                        int i = (int) ((float) ((px - xBegin) * widthEnd) / (float) xEnd);
                        int j = (int) ((float) ((py - yBegin) * heightEnd) / (float) yEnd);
                        
                        mosaic.setRGB(px, py, frame.getRGB(i, j));
                    }
            }
        }
        
        return mosaic;
    }
    
    /**
     * Open video from absolute path.
     * @param video Video file.
     * @throws org.bytedeco.javacv.FrameGrabber.Exception FrameGrabber exception.
     */
    public void open(File video) throws FrameGrabber.Exception {
        /* Close any previous video */
        if (grabber != null) close();
        
        /* Video file metadata */
        String path = video.getAbsolutePath();
        
        /* Open video */
        grabber = new FFmpegFrameGrabber(path);
        grabber.start();
        
        /* Video multimedia metadata */
        metadata = new Metadata(video, grabber.getFormat().toUpperCase(), grabber.getImageWidth(), grabber.getImageHeight(), grabber.getLengthInTime(), grabber.getLengthInFrames(), grabber.getFrameRate());
    }
    
    /**
     * Close current video, release resources que reset metadata.
     * @throws org.bytedeco.javacv.FrameGrabber.Exception FrameGrabber exception.
     */
    public void close () throws FrameGrabber.Exception {
        /* Close video */
        if (grabber != null) {
            grabber.close();
            grabber = null;
        }
        
        /* Clear metadata */
        metadata = null;
        framesSamples.clear();
    }
    
    /**
     * Create and return photomosaic from video frames sampled with the current
     * level. If level is less or equal zero makes total sampling.
     * @param frameNumber Number of the frame to mosaicate.
     * @param div Number of divisions.
     * @param interval Interval of frames to sample.
     * @param scale Scale of the mosaic.
     * @param level Sampling level.
     * @return A photomosaic.
     * @throws org.bytedeco.javacv.FrameGrabber.Exception Frame grabber exception.
     */
    public BufferedImage getMosaic(int frameNumber, int div, int interval, float scale, int level) throws FrameGrabber.Exception {
        /* Total sampling */
        if (level <= 0) return getMosaic(frameNumber, div, interval, scale);
        
        /* Process video frames */
        if ((gap != interval) || (samplingLevel != level)) {
            gap = interval;
            samplingLevel = level;
            sampleFrames();
        }
        
        /* Search source frames */
        if ((frameID != frameNumber) || (divisions != div)) {
            frameID = frameNumber;
            divisions = div;
            searchSources(frameNumber);
        }
        
        /* Build mosaic */
        return buildMosaic(scale);
    }
    
    /**
     * Create and return photomosaic from video frames totally sampled.
     * @param frameNumber Number of the frame to mosaicate.
     * @param div Number of divisions.
     * @param interval Interval of frames to sample.
     * @param scale Scale of the mosaic.
     * @return A photomosaic.
     * @throws org.bytedeco.javacv.FrameGrabber.Exception Frame grabber exception.
     */
    public BufferedImage getMosaic(int frameNumber, int div, int interval, float scale) throws FrameGrabber.Exception {
        samplingLevel = 0;
        
        return new BufferedImage(0, 0, BufferedImage.TYPE_3BYTE_BGR);
    }
    
    /**
     * Get video metadata.
     * @return Video metadata.
     */
    public Metadata getMetadata () {
        return metadata;
    }
    
    /**
     * Get the frame especified by number.
     * @param frameNumber Number of frame.
     * @return Buffered image of selected frame.
     * @throws org.bytedeco.javacv.FrameGrabber.Exception Frame grabber exception.
     */
    public BufferedImage getFrame(int frameNumber) throws FrameGrabber.Exception {
        if (grabber == null) return null;
        grabber.setFrameNumber(frameNumber);
        BufferedImage frame = TO_BUFFERED_IMAGE.convert(grabber.grab());
        if (frame == null) return null;
        
        ColorModel colorModel = frame.getColorModel();
        boolean isAlphaPremultiplied = colorModel.isAlphaPremultiplied();
        WritableRaster raster = frame.copyData(null);
        
        return new BufferedImage(colorModel, raster, isAlphaPremultiplied, null);
    }
    
    /**
     * Get the source frames array.
     * @return Source frames array.
     */
    public BufferedImage[] getSourceFrames() {
        return sourceFrame;
    }
}
