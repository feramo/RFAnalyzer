package com.mantz_it.rfanalyzer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

/**
 * <h1>RF Analyzer - Analyzer Surface</h1>
 *
 * Module:      AnalyzerSurface.java
 * Description: This is a custom view extending the SurfaceView.
 *              It will show the frequency spectrum and the waterfall
 *              diagram.
 *
 * @author Dennis Mantz
 *
 * Copyright (C) 2014 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation; either
 * version 2 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */
public class AnalyzerSurface extends SurfaceView implements SurfaceHolder.Callback,
															ScaleGestureDetector.OnScaleGestureListener,
															GestureDetector.OnGestureListener,
															GestureDetector.OnDoubleTapListener {

	private ScaleGestureDetector scaleGestureDetector = null;
	private GestureDetector gestureDetector = null;

	private UserInputListener userInputListener = null;

	private Paint defaultPaint = null;		// Paint object to draw bitmaps on the canvas
	private Paint blackPaint = null;		// Paint object to draw black (erase)
	private Paint fftPaint = null;			// Paint object to draw the fft lines
	private Paint waterfallLinePaint = null;// Paint object to draw one waterfall pixel
	private Paint textPaint = null;			// Paint object to draw text on the canvas
	private int width;						// current width (in pixels) of the SurfaceView
	private int height;						// current height (in pixels) of the SurfaceView

	private static final String logtag = "AnalyzerSurface";
	private static final long MIN_FREQUENCY = 10000000l;
	private static final long MAX_FREQUENCY = 6000000000l;
	private static final int MIN_DB = -100;
	private static final int MAX_DB = 10;
	private static final int MAX_SAMPLERATE = 20000000;
	private static final int MIN_SAMPLERATE = 10000;

	private int[] waterfallColorMap = null;		// Colors used to draw the waterfall plot.
												// idx 0 -> weak signal   idx max -> strong signal
	private Bitmap[] waterfallLines = null;		// Each array element holds one line in the waterfall plot
	private int waterfallLinesTopIndex = 0;		// Indicates which array index in waterfallLines is the most recent (circular array)

	// virtual frequency and sample rate indicate the current visible viewport of the fft. they vary from
	// the actual values when the user does scrolling and zooming
	private long virtualFrequency = -1;		// Center frequency of the fft (baseband) AS SHOWN ON SCREEN
	private int virtualSampleRate = -1;		// Sample Rate of the fft
	private float minDB = -35;				// Lowest dB on the scale
	private float maxDB = -5;					// Highest dB on the scale

	private float fftRatio = 0.5f;					// percentage of the height the fft consumes on the surface
	private float waterfallRatio = 1 - fftRatio;	// percentage of the height the waterfall consumes on the surface

	/**
	 * Constructor. Will initialize the Paint instances and register the callback
	 * functions of the SurfaceHolder
	 *
	 * @param context
	 */
	public AnalyzerSurface(Context context, UserInputListener userInputListener) {
		super(context);
		this.userInputListener = userInputListener;
		this.defaultPaint = new Paint();
		this.blackPaint = new Paint();
		this.blackPaint.setColor(Color.BLACK);
		this.fftPaint = new Paint();
		this.fftPaint.setColor(Color.BLUE);
		this.fftPaint.setStyle(Paint.Style.FILL);
		this.textPaint = new Paint();
		this.textPaint.setColor(Color.WHITE);
		this.waterfallLinePaint = new Paint();

		// Add a Callback to get informed when the dimensions of the SurfaceView changes:
		this.getHolder().addCallback(this);

		// Create the color map for the waterfall plot (should be customizable later)
		this.createWaterfallColorMap();

		// Instantiate the gesture detector:
		this.scaleGestureDetector = new ScaleGestureDetector(context, this);
		this.gestureDetector = new GestureDetector(context, this);
	}

	/**
	 * Sets the power range (minDB and maxDB on the scale).
	 * Note: we have to make sure this is an atomic operation to not interfere with the
	 * processing/drawing thread.
	 *
	 * @param minDB		new lowest dB value on the scale
	 * @param maxDB		new highest dB value on the scale
	 */
	public void setDBScale(float minDB, float maxDB) {
		synchronized (this.getHolder()) {
			this.minDB = minDB;
			this.maxDB = maxDB;
		}
	}

	/**
	 * Will move the frequency scale so that the desired frequency is centered
	 *
	 * @param frequency		frequency that should be centered on the screen
	 */
	public void centerAroundFrequency(long frequency) {
		this.virtualFrequency = frequency;
	}

	/**
	 * Will initialize the waterfallLines array for the given width and height of the waterfall plot.
	 * If the array is not null, it will be recycled first.
	 */
	private void createWaterfallLineBitmaps() {
		// Recycle bitmaps if not null:
		if(this.waterfallLines != null) {
			for(Bitmap b: this.waterfallLines)
				b.recycle();
		}

		// Create new array:
		this.waterfallLinesTopIndex = 0;
		this.waterfallLines = new Bitmap[getWaterfallHeight()/getPixelPerWaterfallLine()];
		for (int i = 0; i < waterfallLines.length; i++)
			waterfallLines[i] = Bitmap.createBitmap(width,getPixelPerWaterfallLine(), Bitmap.Config.ARGB_8888);
	}

	/**
	 * Will populate the waterfallColorMap array with color instances
	 */
	private void createWaterfallColorMap() {
		this.waterfallColorMap = new int[512];
		for (int i = 0; i < 512; i++) {
			int blue = i <= 255 ? i : 511 - i;
			int red  = i <= 255 ? 0 : i - 256;
			waterfallColorMap[i] = Color.argb(0xff, red, 0, blue);
		}
	}

//------------------- <SurfaceHolder.Callback> ------------------------------//
	/**
	 * SurfaceHolder.Callback function. Gets called when the surface view is created.
	 * We do all the work in surfaceChanged()...
	 *
	 * @param holder	reference to the surface holder
	 */
	@Override
	public void surfaceCreated(SurfaceHolder holder) {

	}

	/**
	 * SurfaceHolder.Callback function. This is called every time the dimension changes
	 * (and after the SurfaceView is created).
	 *
	 * @param holder	reference to the surface holder
	 * @param format
	 * @param width		current width of the surface view
	 * @param height	current height of the surface view
	 */
	@Override
	public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
		this.width = width;
		this.height = height;

		// Recreate the shaders:
		this.fftPaint.setShader(new LinearGradient(0, 0, 0, getFftHeight(), Color.WHITE, Color.BLUE, Shader.TileMode.MIRROR));

		// Recreate the waterfall bitmaps:
		this.createWaterfallLineBitmaps();

		// Fix the text size:
		this.textPaint.setTextSize((int) (getGridSize()/2.1));
	}

	/**
	 * SurfaceHolder.Callback function. Gets called before the surface view is destroyed
	 *
	 * @param holder	reference to the surface holder
	 */
	@Override
	public void surfaceDestroyed(SurfaceHolder holder) {

	}
//------------------- </SurfaceHolder.Callback> -----------------------------//

//------------------- <OnScaleGestureListener> ------------------------------//
	@Override
	public boolean onScale(ScaleGestureDetector detector) {
		float xScale = detector.getCurrentSpanX()/detector.getPreviousSpanX();
		float yScale = detector.getCurrentSpanY()/detector.getPreviousSpanY();
		long frequencyFocus = virtualFrequency + (int)((detector.getFocusX()/width - 0.5)*virtualSampleRate);
		float dBFocus = maxDB - (maxDB-minDB) * (detector.getFocusY() / getFftHeight());

		virtualSampleRate = (int) Math.min( Math.max(virtualSampleRate / xScale, MIN_SAMPLERATE), MAX_SAMPLERATE);
		virtualFrequency = Math.min(Math.max( frequencyFocus + (long) ((virtualFrequency-frequencyFocus)/xScale), MIN_FREQUENCY), MAX_FREQUENCY) ;

		float newMinDB = Math.min(Math.max(dBFocus - (dBFocus - minDB) / yScale, MIN_DB), MAX_DB-10);
		float newMaxDB = Math.min(Math.max(dBFocus - (dBFocus - maxDB) / yScale, newMinDB + 10), MAX_DB);
		this.setDBScale(newMinDB, newMaxDB);
		return true;
	}

	@Override
	public boolean onScaleBegin(ScaleGestureDetector detector) {
		return true;
	}

	@Override
	public void onScaleEnd(ScaleGestureDetector detector) {
	}
//------------------- </OnScaleGestureListener> -----------------------------//

//------------------- <OnGestureListener> -----------------------------------//
	@Override
	public boolean onDown(MotionEvent e) {
		return true;	// not used
	}

	@Override
	public void onShowPress(MotionEvent e) {
		// not used
	}

	@Override
	public boolean onSingleTapUp(MotionEvent e) {
		return true;	// not used
	}

	@Override
	public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {

		virtualFrequency = Math.min(Math.max(virtualFrequency + (long)((virtualSampleRate / width) * distanceX), MIN_FREQUENCY), MAX_FREQUENCY);

		float yDiff = (maxDB-minDB) * (distanceY/(float)getFftHeight());

		// Make sure we stay in the boundaries:
		if(maxDB - yDiff > MAX_DB)
			yDiff = MAX_DB - maxDB;
		if(minDB - yDiff < MIN_DB)
			yDiff = MIN_DB - minDB;

		this.setDBScale(minDB-yDiff, maxDB-yDiff);
		return true;
	}

	@Override
	public void onLongPress(MotionEvent e) {
		// not used
	}

	@Override
	public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
		return true;
	}
//------------------- </OnGestureListener> ----------------------------------//

//------------------- <OnDoubleTapListener> ---------------------------------//
	@Override
	public boolean onSingleTapConfirmed(MotionEvent e) {
		return true;	// not used
	}

	@Override
	public boolean onDoubleTap(MotionEvent e) {
		if(userInputListener != null && virtualFrequency > 0 && virtualSampleRate > 0) {
			userInputListener.onSetFrequencyAndSampleRate(virtualFrequency, virtualSampleRate);
		}
		return true;
	}

	@Override
	public boolean onDoubleTapEvent(MotionEvent e) {
		return true;	// not used
	}
//------------------- </OnDoubleTapListener> --------------------------------//

	@Override
	public boolean onTouchEvent(MotionEvent event) {
		boolean retVal = this.scaleGestureDetector.onTouchEvent(event);
		retVal = this.gestureDetector.onTouchEvent(event) || retVal;
		return retVal;
	}


	/**
	 * Returns the height of the fft plot in px (y coordinate of the bottom line of the fft spectrum)
	 *
	 * @return heigth (in px) of the fft
	 */
	private int getFftHeight() {
		return (int) (height * fftRatio);
	}

	/**
	 * Returns the height of the waterfall plot in px
	 *
	 * @return heigth (in px) of the waterfall
	 */
	private int getWaterfallHeight() {
		return (int) (height * waterfallRatio);
	}

	/**
	 * Returns the height/width of the frequency/power grid in px
	 *
	 * @return size of the grid (frequency grid height / power grid width) in px
	 */
	private int getGridSize() {
		return (int) (75 * getResources().getDisplayMetrics().xdpi/200);
	}

	/**
	 * Returns height (in pixel) of each line in the waterfall plot
	 *
	 * @return number of pixels (in vertical direction) of one line in the waterfall plot
	 */
	private int getPixelPerWaterfallLine() {
		return 3;
	}

	/**
	 * Will (re-)draw the given data set on the surface. Note that it actually only draws
	 * a sub set of the fft data depending on the current settings of virtual frequency and sample rate.
	 *
	 * @param mag			array of magnitude values that represent the fft
	 * @param frequency		center frequency
	 * @param sampleRate	sample rate
	 * @param frameRate 	current frame rate (FPS)
	 * @param load			current load (percentage [0..1])
	 */
	public void draw(double[] mag, long frequency, int sampleRate, int frameRate, double load) {

		if(virtualFrequency < 0)
			virtualFrequency = frequency;
		if(virtualSampleRate < 0)
			virtualSampleRate = sampleRate;

		// Calculate the start and end index to draw mag according to frequency and sample rate and
		// the virtual frequency and sample rate:
		float samplesPerHz = (float) mag.length/ (float) sampleRate;	// indicates how many samples in mag cover 1 Hz
		long frequencyDiff = virtualFrequency - frequency;				// difference between center frequencies
		int sampleRateDiff = virtualSampleRate - sampleRate;			// difference between sample rates
		int start = (int)((frequencyDiff - sampleRateDiff/2.0) * samplesPerHz);
		int end = mag.length + (int)((frequencyDiff + sampleRateDiff/2.0) * samplesPerHz);

		Canvas c = null;
		try {
			c = this.getHolder().lockCanvas();

			synchronized (this.getHolder()) {
				if(c != null) {
					// Draw all the components
					drawFFT(c, mag, start, end);
					drawWaterfall(c);
					drawFrequencyGrid(c);
					drawPowerGrid(c);
					drawPerformanceInfo(c, frameRate, load);
				} else
					Log.d(logtag, "draw: Canvas is null.");
			}
		} catch (Exception e)
		{
			Log.e(logtag,"draw: Error while drawing on the canvas. Stop!");
			e.printStackTrace();
		} finally {
			if (c != null) {
				this.getHolder().unlockCanvasAndPost(c);
			}
		}
	}

	/**
	 * This method will draw the fft onto the canvas. It will also update the bitmap in
	 * waterfallLines[waterfallLinesTopIndex] with the data from mag.
	 * Important: start and end may be out of bounds of the mag array. This will cause black
	 * padding.
	 *
	 * @param c			canvas of the surface view
	 * @param mag		array of magnitude values that represent the fft
	 * @param start		first index to draw from mag (may be negative)
	 * @param end		last index to draw from mag (may be > mag.length)
	 */
	private void drawFFT(Canvas c, double[] mag, int start, int end) {
		float sampleWidth 	= (float) width / (float) (end-start);		// Size (in pixel) per one fft sample
		float dbDiff 		= maxDB - minDB;
		float dbWidth 		= getFftHeight() / dbDiff; 	// Size (in pixel) per 1dB in the fft
		float scale 		= this.waterfallColorMap.length / dbDiff;	// scale for the color mapping of the waterfall

		// Get a canvas from the bitmap of the current waterfall line and clear it:
		Canvas newline = new Canvas(waterfallLines[waterfallLinesTopIndex]);
		newline.drawColor(Color.BLACK);

		// Clear the fft area in the canvas:
		c.drawRect(0, 0, width, getFftHeight(), blackPaint);

		// The start position to draw is either 0 or greater 0, if start is negative:
		float position = start>=0 ? 0 : sampleWidth * start * -1;

		// Draw sample by sample:
		for (int i = Math.max(start,0); i < mag.length; i++) {
			// FFT:
			if(mag[i] > minDB) {
				float topPixel = (float) (getFftHeight() - (mag[i] - minDB) * dbWidth);
				if(topPixel < 0 ) topPixel = 0;
				c.drawRect(position, topPixel, position + sampleWidth, getFftHeight(), fftPaint);
			}

			// Waterfall:
			if(mag[i] <= minDB)
				waterfallLinePaint.setColor(waterfallColorMap[0]);
			else if(mag[i] >= maxDB)
				waterfallLinePaint.setColor(waterfallColorMap[waterfallColorMap.length-1]);
			else
				waterfallLinePaint.setColor(waterfallColorMap[(int)((mag[i]-minDB)*scale)]);
			newline.drawRect(position, 0, position + sampleWidth, getPixelPerWaterfallLine(), waterfallLinePaint);

			// Shift position:
			position += sampleWidth;
		}
	}

	/**
	 * This method will draw the waterfall plot onto the canvas.
	 *
	 * @param c			canvas of the surface view
	 */
	private void drawWaterfall(Canvas c) {
		// draw the bitmaps on the canvas:
		for (int i = 0; i < waterfallLines.length; i++) {
			int idx = (waterfallLinesTopIndex + i) % waterfallLines.length;
			c.drawBitmap(waterfallLines[idx], 0, getFftHeight() + i*getPixelPerWaterfallLine(), defaultPaint);
		}

		// move the array index (note that we have to decrement in order to do it correctly)
		waterfallLinesTopIndex--;
		if(waterfallLinesTopIndex < 0)
			waterfallLinesTopIndex += waterfallLines.length;
	}

	/**
	 * This method will draw the frequency grid into the canvas
	 *
	 * @param c				canvas of the surface view
	 */
	private void drawFrequencyGrid(Canvas c) {
		// Calculate pixel width of a minor tick (100KHz)
		float pixelPerMinorTick = (float) (width / (virtualSampleRate/100000.0));

		// Calculate the frequency at the left most point of the fft:
		long startFrequency = (long) (virtualFrequency - (virtualSampleRate/2.0));

		// Calculate the frequency and position of the first Tick (ticks are every 100KHz)
		long tickFreq = (long) Math.ceil(startFrequency/100000.0) * 100000;
		float tickPos = (float) (pixelPerMinorTick / 100000.0 * (tickFreq-startFrequency));

		// Draw the ticks
		for (int i = 0; i < virtualSampleRate/100000; i++) {
			float tickHeight;
			if(tickFreq % 1000000 == 0) {
				// Major Tick (1MHZ)
				tickHeight = (float) (getGridSize() / 2.0);
				// Draw Frequency Text:
				c.drawText("" + tickFreq/1000000, tickPos, getFftHeight()-tickHeight, textPaint);
			} else if(tickFreq % 500000 == 0) {
				// Half MHz tick
				tickHeight = (float) (getGridSize() / 3.0);
			} else {
				// Minor tick
				tickHeight = (float) (getGridSize() / 4.0);
			}
			c.drawLine(tickPos, getFftHeight(), tickPos, getFftHeight() - tickHeight, textPaint);
			tickFreq += 100000;
			tickPos += pixelPerMinorTick;
		}
	}

	/**
	 * This method will draw the power grid into the canvas
	 *
	 * @param c				canvas of the surface view
	 */
	private void drawPowerGrid(Canvas c) {
		// Calculate pixel height of a minor tick (1dB)
		float pixelPerMinorTick = (float) (getFftHeight() / (maxDB-minDB));

		// Draw the ticks from the top to the bottom. Stop as soon as we interfere with the frequency scale
		int tickDB = (int) maxDB;
		float tickPos = (maxDB - tickDB)*pixelPerMinorTick;
		for (; tickDB > minDB; tickDB--) {
			float tickWidth;
			if(tickDB % 10 == 0) {
				// Major Tick (10dB)
				tickWidth = (float) (getGridSize() / 3.0);
				// Draw Frequency Text:
				c.drawText("" + tickDB, (float) (getGridSize() / 2.9), tickPos, textPaint);
			} else if(tickDB % 5 == 0) {
				// 5 dB tick
				tickWidth = (float) (getGridSize() / 3.5);
			} else {
				// Minor tick
				tickWidth = (float) (getGridSize() / 5.0);
			}
			c.drawLine(0, tickPos, tickWidth, tickPos, textPaint);
			tickPos += pixelPerMinorTick;

			// stop if we interfere with the frequency grid:
			if (tickPos > getFftHeight() - getGridSize())
				break;
		}
	}

	/**
	 * This method will draw the performance information into the canvas
	 *
	 * @param c				canvas of the surface view
	 * @param frameRate 	current frame rate (FPS)
	 * @param load			current load (percentage [0..1])
	 */
	private void drawPerformanceInfo(Canvas c, int frameRate, double load) {
		Rect bounds = new Rect();
		String text;

		// Draw the FFT/s rate
		text = frameRate+" FPS";
		textPaint.getTextBounds(text,0 , text.length(), bounds);
		c.drawText(text,width-bounds.width(),bounds.height(), textPaint);

		// Draw the load
		text = String.format("%3.1f %%", load * 100);
		textPaint.getTextBounds(text,0 , text.length(), bounds);
		c.drawText(text,width-bounds.width(),bounds.height() * 2,textPaint);
	}

	/**
	 * Listener Interface for user input
	 */
	public static interface UserInputListener {
		/**
		 * This method will be called when the user double taps the screen and wants to
		 * set the frequency and sample rate of the source accordingly
		 *
		 * @param frequency		Requested frequency to tune to
		 * @param sampleRate	Requested sample rate to set
		 */
		public void onSetFrequencyAndSampleRate(long frequency, int sampleRate);
	}
}
