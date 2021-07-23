import com.pi4j.io.spi.SpiChannel;
import com.pi4j.io.spi.SpiDevice;
import com.pi4j.io.spi.SpiFactory;
import com.pi4j.io.gpio.*;
 
import java.util.*;
import java.io.*;
 
public class DotStar {
	private static SpiDevice spi = null; /// Dotstar spi device
	private int numLEDs;   /// Number of pixels
  private byte dataPin;    /// If soft SPI, data pin #
  private byte clockPin;   /// If soft SPI, clock pin #
  private byte brightness; /// Global brightness setting
  private int pixels[];   /// LED RGB values (3 bytes ea.)
  private byte rOffset;    /// Index of red in 3-byte pixel
  private byte gOffset;    /// Index of green byte
  private byte bOffset;    /// Index of blue byte
	
	public DotStar(int num_leds, String order) throws IOException{
		begin();
		setColorOrder(order);
		updateLength(num_leds);
		clear();
		show();
	}
	
	public DotStar(int num_leds) throws IOException{
		this(num_leds, "BGR");
	}
	
	/**
	 * Starts the SPI connection.
	 */
	public void begin() throws IOException{
		spi = SpiFactory.getInstance(SpiChannel.CS0,8000000); 
	}
	
	/**
	 * Sets the indeces of each byte in the 3 byte pixel.
	 * 
	 * @param order String representation of the order of pixels.
	 * 				(e.g. "RGB")
   */
	public void setColorOrder(String order){
		rOffset=(byte)order.indexOf('R');
		gOffset=(byte)order.indexOf('G');
		bOffset=(byte)order.indexOf('B');
	}
	
	/**
	 * Change the length of the DotStar strip.
	 * 
	 * @param num_leds The number of LEDs in the strip.
   */
	public void updateLength(int num_leds) {
		numLEDs = num_leds;
		pixels = new int[num_leds*3];
	}
	
	/**
	 * Sets all pixels to OFF.
   */
	public void clear(){
		Arrays.fill(pixels, 0);
	}
	
	/**
	 * Sets all pixels to the specified RGB value. Does not immediately 
	 * affect what's currently displayed on the LEDs. The next call to 
	 * show() will refresh the LEDs to display this change.
	 * 
   * @param   r  Red, 0=minimum (off), 255=brightest.
   * @param   g  Green, 0=minimum (off), 255=brightest.
   * @param   b  Blue, 0=minimum (off), 255=brightest.
   */
	public void fill(int r, int g, int b){
		for(int i =0; i < numLEDs-1; i++){
			setPixelColor(i,r,g,b);
		}
	}
	
	/**
	 * Sets specified pixel to specified RGB value. Does not immediately 
	 * affect what's currently displayed on the LEDs. The next call to 
	 * show() will refresh the LEDs to display this pixel.
	 * 
	 * @param   index  Index of the pixel.
   * @param   r  Red, 0=minimum (off), 255=brightest.
   * @param   g  Green, 0=minimum (off), 255=brightest.
   * @param   b  Blue, 0=minimum (off), 255=brightest.
   */
	public void setPixelColor(int index, int r, int g, int b){
		if(index < numLEDs) {
			pixels[index*3+rOffset]=r;
			pixels[index*3+gOffset]=g;
			pixels[index*3+bOffset]=b;
		}
	}
	
	/**
	 * Adjust output brightness. Does not immediately affect what's
   * currently displayed on the LEDs. The next call to show() will
   * refresh the LEDs at this level.
   * 
   * @param   b  Brightness setting, 0=minimum (off), 255=brightest.
   */
	public void setBrightness(int b) {
		brightness = (byte)(b + 1);
	}
	
	/**
	 * Sends the stored pixel values to the strip.
   */
	public void show() throws IOException{
		if (pixels.length==0)
			return;
			
		// 4 byte start-frame marker
		for (int i = 0; i < 4; i++)
			spi.write((byte)0x00); 
			
		// LED Data
		for(int i = 0; i < numLEDs; i++){
			spi.write((byte)0xFF); // Pixel start
			for(int l = 0; l<3; l++)
				spi.write((byte)pixels[i*3+l]);// Pixel color
		}
    
    // Four end-frame bytes reccomended by datasheet
    // They turn the LED after the last on if the strip is longer than
    // the specified length.
    /**for (int j = 0; j < 4; j++)
     *  spi.write((byte)0xFF); 
     */
   }
}
